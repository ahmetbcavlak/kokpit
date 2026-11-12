package dev.kokpit.kesif

/**
 * The live page for the iPad: it asks /canli.json twice a second and draws the same cards
 * as the head unit. Read-only: the page has no buttons that change anything on the unit.
 * (The JavaScript avoids the dollar sign so this Kotlin string needs no escaping.)
 */
object LivePage {
    fun html(): String = """
<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kokpit Keşif · Canlı</title>
<style>
body{margin:0;background:#0E1114;color:#ECEEEA;font:16px/1.45 -apple-system,system-ui,sans-serif;padding:20px}
h1{font-size:26px;margin:0}small,.m{color:#9AA5AD}a{color:#F0A030}
.top{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.btn{display:inline-block;background:#F0A030;color:#10161B;border-radius:12px;padding:12px 18px;font-weight:600;margin:12px 8px 12px 0;text-decoration:none}
.btn.s{background:#171C21;color:#ECEEEA;border:1px solid #2A333B}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px;margin-top:8px}
.card{background:#171C21;border-radius:16px;padding:14px 16px;border:3px solid transparent;transition:border-color .3s}
.card.f{border-color:#F0A030}
.t{display:flex;justify-content:space-between;color:#9AA5AD;font-weight:600}
.st{font-size:14px;margin:2px 0 8px}.NONE{color:#5E6870}.PRESENT{color:#F0A030}.LIVE{color:#4CC38A}
table{width:100%;border-collapse:collapse;font:13px/1.4 ui-monospace,Menlo,monospace}
td{padding:3px 4px;border-top:1px solid #222a31;vertical-align:top;word-break:break-all}
td.v{color:#ECEEEA;font-weight:600;white-space:nowrap}
.rec{font-weight:700}.on{color:#FF6B5B}
.ev td{color:#9AA5AD}
</style></head><body>
<div class="top"><div><h1>Kokpit Keşif · Canlı araç verisi</h1>
<small>Gri: veri yok · Kehribar: değer var ama bu oturumda değişmedi, eski olabilir · Yeşil: değişti (sen bir şey yapınca değişiyorsa veri canlı)</small></div>
<div class="rec" id="rec">…</div></div>
<a class="btn" href="/kayit.csv">Kaydı indir (CSV)</a><a class="btn s" href="/kayit.json">JSON</a>
<a class="btn s" href="/rapor">Ekran raporu</a><a class="btn s" href="/paketler">Paketler</a><a class="btn s" href="/pencereler.csv">Pencere olayları</a><a class="btn s" href="/arayuz.csv">Arayüz dökümü</a><a class="btn s" href="/servis-arayuzu.txt">Servis arayüzü</a><a class="btn s" href="/klima-komutu.csv">Klima denemesi</a>
<div class="grid" id="g"></div>
<h3>Son değişiklikler</h3><table class="ev" id="ev"></table>
<h3>Kayıt dosyaları</h3><div id="files" class="m"></div>
<p class="m" id="diag"></p>
<script>
function e(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function rows(keys){return '<table>'+keys.map(function(k){
  return '<tr><td>'+e(k.label)+'</td><td class="v">'+e(k.value||'—')+'</td><td>'+(k.changes?k.changes+' · '+e(k.ago):'')+'</td></tr>'}).join('')+'</table>'}
function tm(ms){var d=new Date(ms);return d.toTimeString().slice(0,8)}
var last={};
function draw(d){
  var h='';
  d.signals.forEach(function(s){
    var fresh=last[s.id]!==undefined&&last[s.id]!==s.changes;last[s.id]=s.changes;
    h+='<div class="card'+(fresh?' f':'')+'"><div class="t"><span>'+e(s.title)+'</span><span>'+e(s.item)+'</span></div>'+
       '<div class="st '+s.status+'">● '+e(s.statusText)+(s.changes?' · '+s.changes+' değişim · '+e(s.ago):'')+'</div>'+
       (s.keys.length?rows(s.keys):'<span class="m">Anahtar henüz görülmedi.</span>')+'</div>';
  });
  h+='<div class="card"><div class="t"><span>Listede olmayan, değişen değişkenler</span></div>'+
     '<div class="st m">Vites ve far anahtarını burada arıyoruz</div>'+(d.unknown.length?rows(d.unknown.map(function(k){k.label=k.key;return k})):'<span class="m">Henüz yok</span>')+'</div>';
  document.getElementById('g').innerHTML=h;
  document.getElementById('ev').innerHTML=d.events.map(function(v){
    return '<tr><td>'+tm(v.at)+'</td><td>'+e(v.kind)+'</td><td>'+e(v.key)+'</td><td class="v">'+e(v.old)+' → '+e(v.new)+'</td></tr>'}).join('');
  var r=d.recording;
  document.getElementById('rec').innerHTML=r.on?'<span class="on">● KAYIT '+e(r.duration)+'</span> · '+r.rows+' satır'+(r.mark?' · son işaret '+e(r.mark):''):'<span class="m">kayıt kapalı</span>';
  document.getElementById('files').innerHTML=r.files.length?r.files.map(function(f){
    return '<a href="/kayit/'+encodeURIComponent(f.name)+'">'+e(f.name)+'</a> · '+Math.ceil(f.bytes/1024)+' KB'}).join('<br>'):'Henüz kayıt yok.';
  var g=d.diag;document.getElementById('diag').textContent='Okuma: '+g.method+' · tarama '+g.scans+' · '+g.scanKeys+' değişken · '+g.scanMs+' ms'+(g.error?' · hata: '+g.error:'');
}
function poll(){
  var x=new XMLHttpRequest();x.open('GET','/canli.json?'+Date.now());x.timeout=3000;
  x.onload=function(){try{draw(JSON.parse(x.responseText))}catch(err){}setTimeout(poll,500)};
  x.onerror=x.ontimeout=function(){document.getElementById('rec').textContent='Ekrana ulaşılamıyor…';setTimeout(poll,2000)};
  x.send();
}
poll();
</script></body></html>
""".trimIndent()
}
