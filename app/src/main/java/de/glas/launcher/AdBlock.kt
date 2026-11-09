package de.glas.launcher

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocker of the Glas Browser, in three layers:
 * 1. Requests to ad and tracking servers are not loaded at all (built-in list plus Peter Lowe's
 *    ad server list, fetched once a week).
 * 2. Ad boxes that remain on the page are hidden with CSS.
 * 3. On YouTube, video ads are skipped: muted, run to their end, and "Skip" is pressed.
 * Sites the user allows (because something broke) are left alone.
 */
object AdBlock {
    private const val LIST_URL = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
    private const val LIST_FILE = "adservers.txt"
    private const val WEEK = 7 * 24 * 3600_000L

    /** Ad, tracking and pop-up networks. Subdomains are blocked too. */
    private val builtIn = setOf(
        // Google ads
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "adservice.google.com",
        "adservice.google.de", "pagead2.googlesyndication.com", "google-analytics.com", "imasdk.googleapis.com",
        "2mdn.net", "admob.com", "adsense.com",
        // Ad exchanges and networks
        "adnxs.com", "adsrvr.org", "advertising.com", "amazon-adsystem.com", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com", "indexww.com", "smartadserver.com", "adform.net", "adition.com",
        "yieldlab.net", "theadex.com", "improvedigital.com", "smaato.net", "teads.tv", "taboola.com", "outbrain.com", "plista.com",
        "ligatus.com", "revcontent.com", "mgid.com", "zemanta.com", "sharethrough.com", "triplelift.com", "33across.com",
        "media.net", "contextweb.com", "spotxchange.com", "spotx.tv", "springserve.com", "freewheel.tv", "stickyadstv.com",
        "adsafeprotected.com", "moatads.com", "doubleverify.com", "serving-sys.com", "flashtalking.com", "adroll.com",
        "bidswitch.net", "mathtag.com", "turn.com", "bluekai.com", "demdex.net", "everesttech.net", "rlcdn.com", "adsymptotic.com",
        "lijit.com", "sovrn.com", "gumgum.com", "yieldmo.com", "sonobi.com", "districtm.io", "emxdgt.com", "onetag-sys.com",
        "adhese.com", "adscale.de", "stroeerdigitalgroup.de", "stroeerdigitalmedia.de", "orbidder.otto.de",
        "adalliance.io", "ad-srv.net", "adtech.de", "adtechus.com", "yieldlove.com", "yieldlove-ad-serving.net", "qualitymedianetwork.de",
        "seedtag.com", "nativery.com", "content-recommendation.net", "ad.360yield.com", "360yield.com", "eyeota.net",
        "zedo.com", "adblade.com", "infolinks.com", "propellerads.com", "popads.net", "popcash.net", "adcash.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "hilltopads.net", "adsterra.com", "clickadu.com", "onclickads.net", "propu.sh",
        "a-ads.com", "bidvertiser.com", "revenuehits.com", "mobsuite.com", "admixer.net", "inmobi.com", "applovin.com",
        "unityads.unity3d.com", "vungle.com", "chartboost.com", "ironsrc.com", "adcolony.com", "mopub.com",
        // Tracking and analytics
        "scorecardresearch.com", "quantserve.com", "chartbeat.com", "chartbeat.net", "hotjar.com", "mouseflow.com",
        "crazyegg.com", "newrelic.com", "nr-data.net", "segment.io", "mixpanel.com", "amplitude.com", "branch.io",
        "adjust.com", "appsflyer.com", "kochava.com", "ioam.de", "xiti.com", "etracker.com", "econda-monitor.de", "webtrekk.net",
        "wt-safetag.com", "mookie1.com", "krxd.net", "agkn.com", "adsensecustomsearchads.com", "facebook.net",
        "connect.facebook.net", "ads.linkedin.com", "analytics.twitter.com", "ads-twitter.com", "ads.tiktok.com",
        "analytics.tiktok.com", "bat.bing.com", "ads.yahoo.com", "advertising.yahoo.com", "adtech.yahooinc.com",
        "id5-sync.com", "liadm.com", "tapad.com", "ml314.com", "permutive.com", "permutive.app",
    )

    /** Parts of addresses that are ads on otherwise wanted servers (YouTube, Twitch …). */
    private val adPaths = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/ad_break", "doubleclick", "/adview",
        "/ads?", "/ad.js", "/ads.js", "/adsbygoogle", "/prebid", "/gampad/",
    )

    /** Video sites and their servers; the downloaded list may never block these (the built-in one is checked). */
    private val neverFromList = listOf(
        "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com", "googleapis.com", "google.com",
        "ardmediathek.de", "ard.de", "zdf.de", "arte.tv", "3sat.de", "vimeo.com", "vimeocdn.com", "dailymotion.com",
        "dmcdn.net", "twitch.tv", "ttvnw.net", "jtvnw.net", "akamaihd.net", "akamaized.net", "cloudfront.net",
    )

    @Volatile private var downloaded: Set<String> = emptySet()
    @Volatile private var loaded = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("adBlock", true)
    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("adBlock", on).apply()

    /** Sites where ads are allowed again, by host without "www.". */
    fun allowed(ctx: Context): Set<String> = sp(ctx).getStringSet("adAllowed", emptySet())!!.toSet()
    fun isAllowed(ctx: Context, pageHost: String?) = pageHost != null && site(pageHost) in allowed(ctx)
    fun setAllowed(ctx: Context, pageHost: String, allow: Boolean) {
        val s = site(pageHost)
        sp(ctx).edit().putStringSet("adAllowed", if (allow) allowed(ctx) + s else allowed(ctx) - s).apply()
    }

    fun site(host: String) = host.lowercase().removePrefix("www.").removePrefix("m.")

    /** Loads the stored list and fetches a fresh one when it's older than a week. Call from a background thread. */
    fun prepare(ctx: Context) {
        val file = File(ctx.filesDir, LIST_FILE)
        if (!loaded) {
            downloaded = runCatching { parse(file.readText()) }.getOrDefault(emptySet())
            loaded = true
        }
        if (System.currentTimeMillis() - file.lastModified() < WEEK && downloaded.isNotEmpty()) return
        runCatching {
            val con = URL(LIST_URL).openConnection() as HttpURLConnection
            con.connectTimeout = 10_000
            con.readTimeout = 20_000
            val text = con.inputStream.bufferedReader().use { it.readText() }
            val hosts = parse(text)
            // Only take a list that looks right, not an error page
            if (hosts.size > 500) {
                file.writeText(text)
                downloaded = hosts
            }
        }
    }

    private fun parse(text: String) = text.lineSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('.') && !it.contains(' ') }
        .toHashSet()

    /** Whether a request from a page should not be loaded. */
    fun blocks(url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (hostListed(host)) return true
        val rest = url.encodedPath.orEmpty() + (url.encodedQuery?.let { "?$it" } ?: "")
        return adPaths.any { rest.contains(it) }
    }

    private fun hostListed(host: String): Boolean {
        val useList = neverFromList.none { host == it || host.endsWith(".$it") }
        var h = host
        while (true) {
            if (h in builtIn || (useList && h in downloaded)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Whether a whole page (not just a part of it) is an ad, e.g. a pop-under redirect. */
    fun blocksPage(url: Uri) = url.host?.lowercase()?.let { hostListed(it) } == true

    /** Hides ad boxes that are still on the page, and skips YouTube video ads. */
    const val PAGE_SCRIPT = "(function(){if(window.__glasAds)return;window.__glasAds=1;" +
        "var css='ins.adsbygoogle,[id^=\"google_ads\"],[id^=\"div-gpt-ad\"],[id*=\"taboola\"],[class*=\"taboola\"],.OUTBRAIN,[id*=\"outbrain\"]," +
        "iframe[src*=\"doubleclick\"],iframe[src*=\"googlesyndication\"],iframe[id^=\"google_ads\"],[data-ad-slot],[data-ad-unit],[data-google-query-id]," +
        ".adsbox,.ad-banner,.ad-container,.ad-wrapper,.ad-slot,.adslot,.advert,.advertisement,.sponsored-content,#ad-banner,#ad-container," +
        "ytd-ad-slot-renderer,ytd-banner-promo-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer," +
        "ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-player-legacy-desktop-watch-ads-renderer," +
        "ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-companion-slot,ad-slot-renderer," +
        "ytm-ad-slot-renderer,.ytp-ad-overlay-container,.ytp-ad-image-overlay,#player-ads,#masthead-ad,.video-ads.ytp-ad-module ytd-merch-shelf-renderer" +
        "{display:none!important}';" +
        "function addCss(){if(!document.documentElement||document.getElementById('glas-ads'))return;var s=document.createElement('style');s.id='glas-ads';s.textContent=css;(document.head||document.documentElement).appendChild(s)}" +
        "addCss();document.addEventListener('DOMContentLoaded',addCss);" +
        "if(!/(^|\\.)youtube\\.com$/.test(location.hostname))return;" +
        "setInterval(function(){addCss();var p=document.querySelector('.ad-showing,.ad-interrupting');" +
        "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-slot button,.ytm-skip-ad-button,button[class*=\"skip-ad\"]');" +
        "if(b)b.click();if(!p)return;var v=p.querySelector('video')||document.querySelector('video');" +
        "if(v&&isFinite(v.duration)&&v.duration>0){v.muted=true;v.playbackRate=16;if(v.currentTime<v.duration-0.3)v.currentTime=v.duration-0.1}},300)})()"
}
