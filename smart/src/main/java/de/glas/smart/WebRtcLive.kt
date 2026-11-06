package de.glas.smart

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Live picture over WebRTC, the way Smart Life's web viewer does it (modelled on go2rtc, MIT licence):
 * the Tuya cloud hands out the camera's WebRTC login and access to its MQTT broker; offer, answer and
 * ICE candidates travel over MQTT; then the picture comes straight from the camera or a Tuya relay.
 * Unlike HLS this lets us ask for the main stream (HD) or the sub stream (SD).
 */
class WebRtcLive(
    private val ctx: Context,
    private val deviceId: String,
    private val uid: String,
    private val renderer: SurfaceViewRenderer,
    private val listener: Listener,
) {
    interface Listener {
        fun onFailed(reason: String)
        fun onLost()
    }

    companion object {
        val egl: EglBase by lazy { EglBase.create() }
        private var factory: PeerConnectionFactory? = null

        private fun factory(ctx: Context): PeerConnectionFactory = factory ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .createInitializationOptions())
            // Play the doorbell's sound like media (loudspeaker), not like a phone call (earpiece)
            val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory().also { factory = it }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var mqtt: MqttClient? = null
    private var pc: PeerConnection? = null
    private var audio: AudioTrack? = null
    private var video: VideoTrack? = null
    @Volatile private var closed = false
    @Volatile private var offerSent = false
    private val pendingCandidates = ArrayList<String>()
    private val sessionId = (1..6).map { "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"[Random.nextInt(62)] }.joinToString("")
    private var publishTopic = ""
    private var from = ""
    private var motoId = ""
    private var auth = ""
    private var ices = JSONArray()
    private var answerMid = "0"
    private var gotAnswer = false
    private var clarity = false
    private var mainType = 0
    private var subType = 1
    var muted = true
        set(v) {
            field = v
            audio?.setEnabled(!v)
        }

    /** Starts the connection; [hd] chooses the main stream, otherwise the sub stream. */
    fun start(hd: Boolean) {
        thread(name = "webrtc-start") {
            try {
                connect(hd)
            } catch (e: Exception) {
                fail("$step: " + if (e is TuyaException) Tuya.explain(e) else (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private var step = ""

    private fun connect(hd: Boolean) {
        val api = Store(ctx).api()
        step = "WebRTC-Daten"
        val conf = api.webrtcConfig(uid, deviceId)
        if (!conf.optBoolean("supports_webrtc", true)) throw Exception("Kamera kann kein WebRTC")
        auth = conf.optString("auth")
        motoId = conf.optString("moto_id")
        ices = conf.optJSONObject("p2p_config")?.optJSONArray("ices") ?: JSONArray()
        val skill = JSONObject(conf.optString("skill", "{}").ifBlank { "{}" })
        clarity = (skill.optInt("webrtc") and (1 shl 5)) != 0

        // Main stream = highest resolution, sub stream = lowest; HEVC needs a data channel we don't support
        val videos = skill.optJSONArray("videos") ?: JSONArray()
        var best: JSONObject? = null
        var worst: JSONObject? = null
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val px = v.optInt("width") * v.optInt("height")
            if (best == null || px > best.optInt("width") * best.optInt("height")) best = v
            if (worst == null || px < worst.optInt("width") * worst.optInt("height")) worst = v
        }
        val chosen = if (hd) best else worst
        if (chosen?.optInt("codecType") == 4) throw Exception("Kamera sendet H.265, das geht nur über HLS")
        // Skill stream types 2 (main) and 4 (sub) are sent as 0 and 1
        fun mqttType(t: Int) = when (t) { 2 -> 0; 4 -> 1; else -> t }
        mainType = mqttType(best?.optInt("streamType", 2) ?: 2)
        subType = mqttType(worst?.optInt("streamType", 4) ?: 4)

        step = "MQTT-Zugang"
        val hub = api.hubConfig(uid)
        val subscribe = hub.optJSONObject("source_topic")?.optString("ipc") ?: throw Exception("Kein MQTT-Thema")
        publishTopic = (hub.optJSONObject("sink_topic")?.optString("ipc") ?: "")
            .replace("moto_id", motoId).replace("{device_id}", deviceId)
        from = subscribe.split("/").getOrElse(3) { uid }

        step = "MQTT-Verbindung (${hub.optString("url")})"
        val client = MqttClient(hub.getString("url"), hub.getString("client_id"), MemoryPersistence())
        mqtt = client
        client.connect(MqttConnectOptions().apply {
            userName = hub.getString("username")
            password = hub.getString("password").toCharArray()
            connectionTimeout = 20
            keepAliveInterval = 60
            isCleanSession = true
        })
        client.subscribe(subscribe, 1) { _, msg -> onMqtt(msg) }
        if (closed) return
        step = "Verbindungsaufbau"

        main.post { createPeer(if (hd) mainType else subType) }
    }

    private fun createPeer(streamType: Int) {
        if (closed) return
        val servers = (0 until ices.length()).mapNotNull { i ->
            val o = ices.optJSONObject(i) ?: return@mapNotNull null
            val b = PeerConnection.IceServer.builder(o.optString("urls"))
            if (o.has("username")) b.setUsername(o.optString("username")).setPassword(o.optString("credential"))
            b.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = factory(ctx).createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = sendCandidate("a=" + c.sdp)
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        if (streamType == mainType && clarity) send("resolution", 312, JSONObject().put("mode", "webrtc").put("cmdValue", 0))
                    }
                    PeerConnection.PeerConnectionState.FAILED -> main.post { if (!closed) listener.onLost() }
                    else -> {}
                }
            }
            override fun onTrack(t: RtpTransceiver) {
                when (val track = t.receiver.track()) {
                    is VideoTrack -> main.post {
                        video = track
                        track.addSink(renderer)
                    }
                    is AudioTrack -> main.post {
                        audio = track
                        track.setEnabled(!muted)
                    }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: org.webrtc.MediaStream) {}
            override fun onRemoveStream(s: org.webrtc.MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out org.webrtc.MediaStream>) {}
        }) ?: return fail("WebRTC nicht verfügbar")
        pc = peer
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

        peer.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer.setLocalDescription(SdpAdapter(), desc)
                // Tuya cameras choke on header extensions
                val sdp = desc.description.replace(Regex("\r\na=extmap[^\r\n]*"), "")
                thread {
                    send("offer", 302, JSONObject().put("mode", "webrtc").put("sdp", sdp).put("stream_type", streamType)
                        .put("auth", auth).put("datachannel_enable", false).put("token", ices))
                    synchronized(pendingCandidates) {
                        offerSent = true
                        pendingCandidates.forEach { c -> send("candidate", 302, JSONObject().put("mode", "webrtc").put("candidate", c)) }
                        pendingCandidates.clear()
                    }
                }
            }
            override fun onCreateFailure(e: String?) = fail("Angebot fehlgeschlagen: $e")
        }, MediaConstraints())

        // The camera must answer within 20 seconds
        main.postDelayed({ if (!gotAnswer && !closed) fail("Kamera antwortet nicht") }, 20_000)
    }

    private fun sendCandidate(c: String) {
        synchronized(pendingCandidates) {
            if (!offerSent) {
                pendingCandidates += c
                return
            }
        }
        thread { send("candidate", 302, JSONObject().put("mode", "webrtc").put("candidate", c)) }
    }

    private fun onMqtt(msg: MqttMessage) {
        val data = runCatching { JSONObject(String(msg.payload)).getJSONObject("data") }.getOrNull() ?: return
        val header = data.optJSONObject("header") ?: return
        if (header.optString("sessionid") != sessionId) return
        val body = data.optJSONObject("msg") ?: JSONObject()
        main.post {
            val peer = pc ?: return@post
            when (header.optString("type")) {
                "answer" -> {
                    if (gotAnswer) return@post
                    gotAnswer = true
                    val sdp = body.optString("sdp")
                    answerMid = Regex("a=mid:(\\S+)").find(sdp)?.groupValues?.get(1) ?: "0"
                    peer.setRemoteDescription(object : SdpAdapter() {
                        override fun onSetFailure(e: String?) = fail("Antwort der Kamera ungültig: $e")
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "candidate" -> {
                    val c = body.optString("candidate").removePrefix("a=").trimEnd('\r', '\n')
                    if (c.isNotEmpty()) peer.addIceCandidate(IceCandidate(answerMid, 0, c))
                }
                "disconnect" -> if (!closed) listener.onLost()
            }
        }
    }

    /** Switches between main stream (HD) and sub stream (SD) without reconnecting, if the camera can. */
    fun setHd(hd: Boolean): Boolean {
        if (!clarity) return false
        thread { send("resolution", 312, JSONObject().put("mode", "webrtc").put("cmdValue", if (hd) 0 else 1)) }
        return true
    }

    private fun send(type: String, protocol: Int, msg: JSONObject) {
        val client = mqtt ?: return
        if (closed && type != "disconnect") return
        val header = JSONObject().put("type", type).put("from", from).put("to", deviceId).put("sub_dev_id", "")
            .put("sessionid", sessionId).put("moto_id", motoId).put("tid", "")
        val payload = JSONObject().put("protocol", protocol).put("pv", "2.2").put("t", System.currentTimeMillis() / 1000)
            .put("data", JSONObject().put("header", header).put("msg", msg))
        runCatching { client.publish(publishTopic, payload.toString().toByteArray(), 1, false) }
    }

    /** Copies the current picture, for the camera widget. */
    fun snapshot(done: (Bitmap) -> Unit) {
        if (video == null) return
        renderer.addFrameListener({ bmp -> main.post { done(bmp) } }, 0.5f)
    }

    private fun fail(reason: String) {
        main.post {
            if (closed) return@post
            stop()
            listener.onFailed(reason)
        }
    }

    fun stop() {
        if (closed) return
        closed = true
        video?.let { runCatching { it.removeSink(renderer) } }
        video = null
        audio = null
        val peer = pc
        pc = null
        val client = mqtt
        mqtt = null
        thread {
            runCatching {
                if (client != null && client.isConnected) {
                    val header = JSONObject().put("type", "disconnect").put("from", from).put("to", deviceId).put("sub_dev_id", "")
                        .put("sessionid", sessionId).put("moto_id", motoId).put("tid", "")
                    val payload = JSONObject().put("protocol", 302).put("pv", "2.2").put("t", System.currentTimeMillis() / 1000)
                        .put("data", JSONObject().put("header", header).put("msg", JSONObject().put("mode", "webrtc")))
                    client.publish(publishTopic, payload.toString().toByteArray(), 1, false)
                    client.disconnect(500)
                }
                client?.close()
            }
            runCatching {
                peer?.close()
                peer?.dispose()
            }
        }
    }
}

/** SdpObserver with empty defaults. */
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
