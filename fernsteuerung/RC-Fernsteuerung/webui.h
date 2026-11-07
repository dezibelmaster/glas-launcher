// Steuer-Webseite, die der ESP32 an das Handy ausliefert.
#pragma once

const char INDEX_HTML[] = R"rawliteral(<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="theme-color" content="#0d1117">
<title>RC-Fernsteuerung</title>
<style>
:root{--bg:#0d1117;--pad:#161b22;--rand:#30363d;--text:#e6edf3;--grau:#8b949e;--akzent:#2f81f7;--ok:#3fb950;--warn:#d29922;--rot:#f85149}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font:14px/1.3 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;overflow:hidden;touch-action:none;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;overscroll-behavior:none}
button,select,input{font:inherit;color:var(--text)}
button{background:var(--pad);border:1px solid var(--rand);border-radius:10px;padding:8px 14px;cursor:pointer}
#kopf{position:fixed;top:0;left:0;right:0;z-index:3;display:flex;align-items:center;justify-content:space-between;gap:8px;
 padding:max(8px,env(safe-area-inset-top)) max(12px,env(safe-area-inset-right)) 8px max(12px,env(safe-area-inset-left));pointer-events:none}
#kopf>*{pointer-events:auto}
#status{display:flex;gap:6px 12px;align-items:center;flex-wrap:wrap;font-size:13px;color:var(--grau);min-width:0}
#status b{color:var(--text);font-weight:600}
.punkt{width:10px;height:10px;border-radius:50%;background:var(--rot);flex:none}
.punkt.ok{background:var(--ok)}
.knoepfe{display:flex;gap:8px;flex:none}
#scharf{min-width:118px;font-weight:700;letter-spacing:.02em}
#scharf.an{background:var(--rot);border-color:var(--rot);color:#fff}
#feld{position:fixed;inset:0;display:flex}
.haelfte{flex:1;position:relative;display:flex;align-items:center;justify-content:center;padding-top:40px}
.pad{position:relative;border-radius:28px;background:var(--pad);border:1px solid var(--rand)}
.pad::before,.pad::after{content:"";position:absolute;background:var(--rand)}
.pad::before{left:50%;top:10%;bottom:10%;width:1px}
.pad::after{top:50%;left:10%;right:10%;height:1px}
.knopf{position:absolute;left:50%;top:50%;border-radius:50%;background:radial-gradient(circle at 35% 30%,#6ea8ff,var(--akzent));
 box-shadow:0 4px 14px rgba(0,0,0,.5);will-change:transform;z-index:1}
.beschr{position:absolute;bottom:-26px;left:0;right:0;text-align:center;color:var(--grau);font-size:12px}
#balken{position:fixed;left:50%;bottom:max(10px,env(safe-area-inset-bottom));transform:translateX(-50%);display:grid;grid-template-columns:auto 90px 44px;gap:3px 6px;
 align-items:center;font-size:11px;color:var(--grau);font-variant-numeric:tabular-nums;z-index:2;pointer-events:none}
.bar{height:6px;background:var(--pad);border:1px solid var(--rand);border-radius:3px;position:relative;overflow:hidden}
.bar i{position:absolute;top:0;bottom:0;background:var(--akzent)}
.bar.aus i{background:var(--grau)}
#hinweis{position:fixed;left:50%;top:56px;transform:translateX(-50%);background:var(--warn);color:#000;padding:6px 12px;border-radius:8px;font-weight:600;
 z-index:4;opacity:0;transition:opacity .2s;pointer-events:none;text-align:center;max-width:90vw}
#hinweis.zeigen{opacity:1}
#einst{position:fixed;inset:0;z-index:10;background:var(--bg);overflow:auto;touch-action:pan-y;display:none;
 padding:max(14px,env(safe-area-inset-top)) max(16px,env(safe-area-inset-right)) 24px max(16px,env(safe-area-inset-left))}
#einst.offen{display:block}
#einst h2{margin:0 0 12px;font-size:18px;display:flex;justify-content:space-between;align-items:center}
#einst label{display:block}
.zeile{margin:0 0 14px}
.zeile select{width:100%;max-width:420px;background:var(--pad);border:1px solid var(--rand);border-radius:8px;padding:8px}
.kanaele{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:12px}
.kanal{background:var(--pad);border:1px solid var(--rand);border-radius:12px;padding:12px}
.kanal h3{margin:0 0 8px;font-size:15px}
.kanal .regler{display:grid;grid-template-columns:52px 1fr 48px;align-items:center;gap:6px;margin:6px 0;font-size:13px;color:var(--grau)}
.kanal input[type=range]{width:100%;accent-color:var(--akzent)}
.kanal output{text-align:right;color:var(--text);font-variant-numeric:tabular-nums}
.klein{color:var(--grau);font-size:12px;margin-top:16px;line-height:1.5}
</style>
</head>
<body>
<div id="feld">
  <div class="haelfte" id="hl"><div class="pad" id="pl"><div class="knopf"></div><div class="beschr">Gas · K4</div></div></div>
  <div class="haelfte" id="hr"><div class="pad" id="pr"><div class="knopf"></div><div class="beschr">Lenkung K1 · K3</div></div></div>
</div>
<div id="kopf">
  <div id="status"><span class="punkt" id="punkt"></span><b id="verb">Keine Verbindung</b><span id="werte"></span></div>
  <div class="knoepfe">
    <button id="scharf">GESICHERT</button>
    <button id="voll" title="Vollbild">⛶</button>
    <button id="zahnrad" title="Einstellungen">⚙</button>
  </div>
</div>
<div id="balken"></div>
<div id="hinweis"></div>

<div id="einst">
  <h2>Einstellungen <button id="schliessen">Fertig</button></h2>
  <div class="zeile"><label>Modell-Typ<br>
    <select id="typ">
      <option value="auto">Auto / Boot – Gas-Stick federt zur Mitte (Mitte = Stopp)</option>
      <option value="flug">Flugzeug – Gas-Stick bleibt stehen (unten = Motor aus)</option>
    </select></label></div>
  <div class="kanaele" id="kanaele"></div>
  <div class="zeile" style="margin-top:16px"><button id="zuruecksetzen">Alles zurücksetzen</button></div>
  <div class="klein">
    Trimm verschiebt die Mitte, Weg begrenzt den Ausschlag, Expo macht die Mitte feinfühliger.<br>
    Der Gaskanal gibt erst Gas, wenn oben „SCHARF“ aktiv ist. Bei Verbindungsverlust, beim Wechsel in eine andere App
    oder beim Ausschalten des Displays wird automatisch gesichert; das Modell geht nach 0,5 s in den Failsafe.
  </div>
</div>

<script>
"use strict";
const KANAL_NAMEN=["K1 Lenkung / Querruder","K2 Gas","K3 Höhenruder","K4 Seitenruder"];
const GAS=1;
const STANDARD={typ:"auto",k:[0,1,2,3].map(()=>({inv:false,trim:0,weg:100,expo:0}))};
const $=id=>document.getElementById(id);
const clamp=(v,a=-1,b=1)=>Math.min(b,Math.max(a,v));

let cfg=lade();
function lade(){
  try{const s=JSON.parse(localStorage.getItem("rc-cfg"));if(s&&Array.isArray(s.k)&&s.k.length===4)return s;}catch(e){}
  return JSON.parse(JSON.stringify(STANDARD));
}
function speichere(){try{localStorage.setItem("rc-cfg",JSON.stringify(cfg));}catch(e){}}

// Stick-Achsen -1..1  (K1 = rx, K2 = ly, K3 = ry, K4 = lx)
const ax={lx:0,ly:cfg.typ==="flug"?-1:0,rx:0,ry:0};

// ---------- Sticks (Mehrfinger, schwebender Ursprung) ----------
const pads=[];
function stick(haelfte,pad,xKey,yKey){
  const knopf=pad.querySelector(".knopf");
  let pid=null,sx=0,sy=0,s0x=0,s0y=0;
  const radius=()=>pad.clientWidth*0.36;
  haelfte.addEventListener("pointerdown",e=>{
    if(pid!==null)return;
    pid=e.pointerId;
    try{haelfte.setPointerCapture(pid);}catch(x){}
    sx=e.clientX;sy=e.clientY;s0x=ax[xKey];s0y=ax[yKey];
    e.preventDefault();
  });
  haelfte.addEventListener("pointermove",e=>{
    if(e.pointerId!==pid)return;
    const r=radius();
    ax[xKey]=clamp(s0x+(e.clientX-sx)/r);
    ax[yKey]=clamp(s0y-(e.clientY-sy)/r);
  });
  const los=e=>{
    if(e.pointerId!==pid)return;
    pid=null;
    ax[xKey]=0;
    if(!(yKey==="ly"&&cfg.typ==="flug"))ax[yKey]=0;
  };
  haelfte.addEventListener("pointerup",los);
  haelfte.addEventListener("pointercancel",los);
  pads.push({pad,knopf,xKey,yKey,radius});
}
stick($("hl"),$("pl"),"lx","ly");
stick($("hr"),$("pr"),"rx","ry");

function groesse(){
  const halb=innerWidth/2, hoehe=innerHeight-110;
  const s=Math.max(120,Math.min(halb*0.82,hoehe,420));
  for(const p of pads){
    p.pad.style.width=p.pad.style.height=s+"px";
    const k=s*0.28;
    p.knopf.style.width=p.knopf.style.height=k+"px";
    p.knopf.style.margin=(-k/2)+"px 0 0 "+(-k/2)+"px";
  }
}
addEventListener("resize",groesse);groesse();

// ---------- Kanalberechnung ----------
function kurve(v,expo){const e=expo/100;return v*(1-e)+v*v*v*e;}
function kanaele(){
  const roh=[ax.rx,ax.ly,ax.ry,ax.lx];
  return roh.map((v,i)=>{
    const c=cfg.k[i];
    let us;
    if(i===GAS&&cfg.typ==="flug"){
      let g=(v+1)/2*c.weg/100;           // 0..1
      if(c.inv)g=1-g;
      us=1000+g*1000+c.trim;
    }else{
      v=kurve(v,c.expo)*c.weg/100;
      if(c.inv)v=-v;
      us=1500+v*500+c.trim;
    }
    return Math.round(clamp(us,1000,2000));
  });
}

// ---------- Scharf / Gesichert ----------
let scharf=false;
function setScharf(an,grund){
  if(an===scharf){if(grund)hinweis(grund);return;}
  scharf=an;
  const b=$("scharf");
  b.classList.toggle("an",an);
  b.textContent=an?"SCHARF":"GESICHERT";
  if(navigator.vibrate)try{navigator.vibrate(an?[40,60,40]:80);}catch(e){}
  if(grund)hinweis(grund);
}
$("scharf").addEventListener("click",()=>{
  if(scharf){setScharf(false);return;}
  if(!offen){hinweis("Keine Verbindung zum Modell");return;}
  const gasNeutral=cfg.typ==="flug"?ax.ly<-0.9:Math.abs(ax.ly)<0.1;
  if(!gasNeutral){hinweis(cfg.typ==="flug"?"Gas erst ganz nach unten":"Gas-Stick erst loslassen");return;}
  setScharf(true);
});
document.addEventListener("visibilitychange",()=>{if(document.hidden)setScharf(false);});
addEventListener("pagehide",()=>setScharf(false));

let hinweisTimer=0;
function hinweis(t){
  const h=$("hinweis");h.textContent=t;h.classList.add("zeigen");
  clearTimeout(hinweisTimer);hinweisTimer=setTimeout(()=>h.classList.remove("zeigen"),2200);
}

// ---------- Verbindung ----------
let ws=null,offen=false,letzteAntwort=0,rtt=null,tele={};
function verbinde(){
  const host=location.hostname||"192.168.4.1";
  try{ws=new WebSocket("ws://"+host+":81/");}catch(e){setTimeout(verbinde,1000);return;}
  ws.binaryType="arraybuffer";
  ws.onopen=()=>{offen=true;letzteAntwort=performance.now();};
  ws.onclose=()=>{
    const warOffen=offen;offen=false;rtt=null;tele={};
    setScharf(false,warOffen?"Verbindung verloren – gesichert":undefined);
    setTimeout(verbinde,1000);
  };
  ws.onerror=()=>{try{ws.close();}catch(e){}};
  ws.onmessage=e=>{
    if(typeof e.data!=="string")return;
    letzteAntwort=performance.now();
    if(e.data[0]==="p"){rtt=performance.now()-parseFloat(e.data.slice(1));}
    else{try{tele=JSON.parse(e.data);}catch(x){}}
  };
}
verbinde();

// 50 Pakete pro Sekunde
setInterval(()=>{
  if(!offen||ws.readyState!==1)return;
  if(ws.bufferedAmount>36)return;          // Funk hängt: keine alten Werte stauen
  const us=kanaele();
  const b=new Uint8Array(9),dv=new DataView(b.buffer);
  for(let i=0;i<4;i++)dv.setUint16(i*2,us[i],true);
  b[8]=scharf?1:0;
  ws.send(b);
},20);

// Ping + Verbindungswächter
setInterval(()=>{
  if(!offen||ws.readyState!==1)return;
  if(performance.now()-letzteAntwort>3000){try{ws.close();}catch(e){}return;}
  ws.send("p"+performance.now().toFixed(1));
},1000);

// ---------- Anzeige ----------
const balken=$("balken");
const zeilen=[0,1,2,3].map(i=>{
  const n=document.createElement("span");n.textContent="K"+(i+1);
  const bar=document.createElement("div");bar.className="bar";const fill=document.createElement("i");bar.appendChild(fill);
  const w=document.createElement("span");
  balken.append(n,bar,w);
  return{bar,fill,w};
});
function zeichne(){
  for(const p of pads){
    const r=p.radius();
    p.knopf.style.transform="translate("+(ax[p.xKey]*r)+"px,"+(-ax[p.yKey]*r)+"px)";
  }
  const us=kanaele();
  us.forEach((u,i)=>{
    const z=zeilen[i];
    const aus=i===GAS&&!scharf;
    const p=(u-1000)/1000;
    const mitte=(i===GAS&&cfg.typ==="flug")?0:0.5;
    z.fill.style.left=(Math.min(p,mitte)*100)+"%";
    z.fill.style.width=(Math.abs(p-mitte)*100)+"%";
    z.bar.classList.toggle("aus",aus);
    z.w.textContent=aus?"aus":u;
  });
  $("punkt").classList.toggle("ok",offen);
  $("verb").textContent=offen?"Verbunden":"Keine Verbindung";
  const t=[];
  if(offen){
    if(rtt!==null)t.push(Math.round(rtt)+" ms");
    if(typeof tele.rssi==="number")t.push("Signal "+tele.rssi+" dBm");
    if(typeof tele.akku==="number")t.push("Akku "+tele.akku.toFixed(2)+" V");
  }else{
    t.push("WLAN „RC-Modell“ verbinden, dann 192.168.4.1 öffnen");
  }
  $("werte").textContent=t.join(" · ");
  requestAnimationFrame(zeichne);
}
requestAnimationFrame(zeichne);

// ---------- Vollbild ----------
$("voll").addEventListener("click",async()=>{
  try{
    if(!document.fullscreenElement){
      await document.documentElement.requestFullscreen();
      if(screen.orientation&&screen.orientation.lock)screen.orientation.lock("landscape").catch(()=>{});
    }else await document.exitFullscreen();
  }catch(e){hinweis("Vollbild: Seite zum Home-Bildschirm hinzufügen");}
});

// ---------- Einstellungen ----------
function baueEinstellungen(){
  $("typ").value=cfg.typ;
  const box=$("kanaele");box.innerHTML="";
  cfg.k.forEach((c,i)=>{
    const d=document.createElement("div");d.className="kanal";
    d.innerHTML="<h3></h3><label><input type='checkbox' data-f='inv'> Richtung umkehren</label>"+
      regler("Trimm","trim",-100,100,1," µs")+regler("Weg","weg",20,100,1," %")+(i===GAS&&cfg.typ==="flug"?"":regler("Expo","expo",0,80,1," %"));
    d.querySelector("h3").textContent=KANAL_NAMEN[i];
    d.querySelectorAll("[data-f]").forEach(inp=>{
      const f=inp.dataset.f;
      const out=inp.parentElement.querySelector("output");
      if(inp.type==="checkbox")inp.checked=c[f];else{inp.value=c[f];out.textContent=c[f]+inp.dataset.e;}
      inp.addEventListener("input",()=>{
        c[f]=inp.type==="checkbox"?inp.checked:Number(inp.value);
        if(out)out.textContent=c[f]+inp.dataset.e;
        speichere();
      });
    });
    box.appendChild(d);
  });
}
function regler(name,f,min,max,step,einheit){
  return "<div class='regler'><span>"+name+"</span><input type='range' min='"+min+"' max='"+max+"' step='"+step+"' data-f='"+f+"' data-e='"+einheit+"'><output></output></div>";
}
$("zahnrad").addEventListener("click",()=>{setScharf(false);baueEinstellungen();$("einst").classList.add("offen");});
$("schliessen").addEventListener("click",()=>$("einst").classList.remove("offen"));
$("typ").addEventListener("change",e=>{
  cfg.typ=e.target.value;ax.ly=cfg.typ==="flug"?-1:0;speichere();baueEinstellungen();
});
$("zuruecksetzen").addEventListener("click",()=>{
  if(!confirm("Alle Einstellungen zurücksetzen?"))return;
  cfg=JSON.parse(JSON.stringify(STANDARD));ax.ly=0;speichere();baueEinstellungen();
});
document.addEventListener("contextmenu",e=>e.preventDefault());
</script>
</body>
</html>
)rawliteral";
