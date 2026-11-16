# Kokpit

Renault Mégane IV'teki dikey araç ekranı için kendi arayüzümüz.

## ⬇️ Kokpit Keşif'i indir

**[▶ Kokpit Keşif'i indir (APK)](https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-kesif.apk)**

Sürüm: 0.9.0 · 199 KB · 0.4 ve sonrasının üzerine kurulur; 0.2.0 yüklüyse **önce onu kaldır**

0.4.0'dan beri yeni bir imza anahtarı kullanılıyor; 0.2.0'ın üzerine kurulmaz. Ekranda 0.2.0 varsa bir kez:

1. Ekranda Ayarlar > Uygulamalar ve bildirimler > Kokpit Keşif > **Kaldır**. Kaybolan bir şey yok.
2. Ekranın tarayıcısında bu sayfayı açıp yukarıdaki bağlantıya dokun. İnince "Yükle"ye dokun.
   İlk seferde "Bilinmeyen uygulamalar" izni istenirse tarayıcıya bir kez izin ver.

Önceki sürümler `dist/` klasöründe sürüm adıyla duruyor.

## ⬇️ Kokpit Ana Ekran'ı indir

**[▶ Kokpit Ana Ekran'ı indir (APK)](https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-anaekran.apk)**

Sürüm: 0.2.2 · 1055 KB · Keşif'in yanına ayrı bir uygulama olarak kurulur ("Kokpit"); Keşif silinmez. Araçta Keşif denemelerinden **sonra** dene (K-73).

1. Ekranın tarayıcısında bu sayfayı açıp yukarıdaki bağlantıya dokun. İnince "Yükle"ye dokun.
2. Kokpit'i aç. Konum izni sorulursa **İzin ver** (hız GPS'ten gelir).
3. Bir kez: Ayarlar > Erişilebilirlik > **Kokpit kapı uyarısı** > Aç. Yalnız üreticinin kapı/bagaj penceresindeki yazıyı okur; hiçbir şeye dokunmaz, hiçbir şey göndermez.

Sonraki sürümler bunun **üzerine** kurulur (kaldırma): kaldırıp kurmak kapı uyarısı servisini kapatır.

0.2.2: kapı ve bagaj durumu doğrudan aracın sistem değerlerinden okunur (okunamazsa eski yol); üreticinin kapı penceresinde X'e basmak artık "Kapı kapandı." göstermez; teşhis panelinde beş kapı değeri ve can_ignore_doorinfo görünür. CarPlay düğmesi: ZLINK simgeden büyüyerek açılır, Kokpit arkada 0,94'e küçülüp kararır; dönüşte 360 ms'de aydınlanır, açılış klibi tekrar oynamaz. 0.2.1'in üzerine kurulur.

0.2.1: ekrandaki araç gerçek jantla (16" iki renkli, beş cepli); gündüz sürüşte de araç gidiyor görünür (gündüz katmanları; lamba yansıması ve şehir ışıkları yalnız gece). 0.2.0'ın üzerine kurulur.

0.2.0: daha sakin geçişler (yeni klipler, arayüz 0,8 sn'de belirir); gece sürüşünde araç gerçekten gidiyor görünür (dönen tekerlek, akan şerit, uzak ışıklar, lamba yansıması, hafif süspansiyon, kamera); GPS hızı yumuşatılır (getSpeed; 5 sn sinyal yoksa "gps" soluk, 60 sn'de "—"). 0.1.1'in üzerine kurulur.

0.1.1: klima sıcaklığı "23.0℃" biçiminde geldiğinde de görünür; motor kapalıyken ("0.0℃") "—" gösterilir. 0.1.0'ın üzerine kurulur.

## Kokpit Ana Ekran nedir?

Ekranın yeni ana arayüzü, ilk sürüm. **Araca hiçbir komut göndermez**; klima düğmeleri 0.1'de yalnız gösterim.

- **Açılış:** araç sahneye girer (klip), 1,6 sn sonra park sahnesine geçer; dokununca kesilir.
- **Park:** saat, dış sıcaklık, klima (sıcaklık, fan, A/C, oto), kapı ve bagaj durumu.
- **Sürüş:** 10 km/sa üstünde GPS hızı; durunca parka döner.
- **Kapı / bagaj açık:** uyarı; kapanınca kısa "kapandı" onayı. Takılırsa uyarıya uzun bas.
- **Klima paneli:** alt çubukta sol köşe; yalnız gösterim.
- **Alt çubuk:** CarPlay, Harita, Telefon → ZLink · Müzik → medya oynatıcı · Uygulamalar → yüklü uygulamalar.
- **Kare hızı ve teşhis:** sağ üst köşedeki sayaç; uzun basınca teşhis paneli (ham klima değerleri, kapı olayları, klip ölçümü).
- Gece/gündüz teması gün doğumu ve batımına göre.

## Kokpit Keşif nedir?

Ekranı ve araçtan gelen veriyi tanımak için küçük bir uygulama. **Yalnızca okur, hiçbir ayarı değiştirmez.** Araca giden tek komut "Klima komutu" sayfasındaki dört tuştur (K-117); 0.7'deki kapı penceresi kapatma testi de yalnız düğmeyle çalışır.

### 0.9.0 · Yol haritası ölçümleri (Faz 6, K-133)

Canlı ekrandaki "Yol haritası ölçümleri (6)" düğmesi. Hepsi tek dosyaya yazar: `yol-olcumleri.csv` (iPad: `/yol.csv`). Hiçbir ayar kalıcı değişmez.

1. **İnternet:** ağ türü, Wi-Fi adı (yalnız ad), github.com ve raw.githubusercontent.com için DNS süresi ve HTTPS (HEAD) süresi, HTTP kodu ya da hata. "CarPlay kapalı" ve "CarPlay açık" düğmeleri ölçümü etiketler.
2. **Güncellemeyi dene:** indirme bağlantısındaki Keşif'i indirir, sürümünü ve imzasını yüklü Keşif'le karşılaştırır; aynı paket, aynı imza ve en az aynı sürümse sistem yükleyicisini açar (yüklemeyi sen onaylarsın). Kurulum izni durumu, ayar ekranına gidiş, yükleyici sonucu ve güncelleme sonrası ilk açılış yazılır. APK, yalnız o dosyayı salt okunur veren küçük bir içerik sağlayıcıyla (FileProvider yerine) verilir.
3. **Açılış zaman çizelgesi:** "Sonraki açılışları ölç" ile Keşif bildirimle arkada bekler (24 saatte kapanır). Soğuk açılışta çekirdek başlangıcından, sıcak açılışta ekranın yanmasından itibaren: sistem hazır (BOOT_COMPLETED), Keşif süreci ve ilk karesi, Kokpit ve ZLINK penceresinin göründüğü an (erişilebilirlik; Kokpit kendi kaydını paylaşmadığı için pencere, ilk kare değil), ilk GPS konumu, ilk klima yayını. Saatler `elapsedRealtime`.
4. **Dokunuş:** 20 dokunuşta dokunuş zamanından cevabı çizen kareye ve ekrana çıkan kareye kadar geçen süre; ortalama ve p95.
5. **Kısa ses:** 300 ms'lik ses; A) USAGE_ASSISTANCE_SONIFICATION + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, B) odaksız. Önce, çalarken ve sonra müzik durumu; "kısıldı / durdu / değişmedi / duyulmadı" düğmeleri.
6. **Parlaklık:** değer ve mod okunur; "Ayarları değiştirme" izni varsa onayla bir kademe kısılır, 3 sn sonra eski değer geri yazılır (Keşif o arada kapanırsa bir sonraki başlangıçta).

Yeni izinler: paket yükleme isteği, ayarları değiştirme (yalnız parlaklık denemesi), açılışta başlama ve ön plan hizmeti (yalnız açılış ölçümü kuruluyken).

### 0.8.0 · Kapı özellikleri, medya ve bağlantı, klima komutu

Canlı ekranın en üstünde üç yeni sayfa:

- **Kapı özellikleri:** `persist.vendor.cem.cardoor1–5` ile `can_ignore_doorinfo` ve `close_avm_door_flag` 250 ms'de bir okunur. Bu iki bayrağın nerede tutulduğu bilinmediği için sistem özelliklerinde (yaygın önekler ve tam tarama) ve Settings.System/Global/Secure'da aranır; bulunduğu yer değerin yanında yazılır. Sayfa açılınca kayıt başlar, sayfadan çıkınca da sürer; "Ayar Açık turu", "Ayar Kapalı turu" ve serbest işaret düğmeleri. `kapi-ozellikleri.csv` (iPad: `/kapi.csv`).
- **Medya ve bağlantı:** bildirim erişimi izniyle (Ayarlar'dan bir kez) etkin medya oturumları: paket, başlık, sanatçı, albüm, kapak var mı ve boyutu, çalma durumu ve desteklenen eylemler. Medya bildirimleri (ZLINK, com.hcn.* ve medya oturumu olanlar; arama, mesaj ve e-posta bildirimleri tutulmaz). Bluetooth yayınları (ACL bağlandı/koptu, A2DP alıcı, AVRCP kumanda, A2DP, HFP istemci), üretici yayınları (üretici uygulamalarının kodundaki eylem adları, en çok 150), Google Play hizmetleri ve Play Store sürümleri, ZLINK paketi, ağ ve internet durumu. "30 sn ölçümü" başta, her 5 sn'de ve sonda durumun fotoğrafını, arada her değişikliği yazar. `medya.csv` (iPad: `/medya.csv`). Çalma kontrolü göndermez, bildirimlere dokunmaz.
- **Klima komutu (K-117):** `com.hcn.autocan.CanService` servisine bağlanır; servis `com.hcn.autocan.aidl.IAirCtrlServices` arayüzünü bildirmezse hiçbir şey gitmez. Üreticinin APK'sı okunabiliyorsa `TRANSACTION_airCtrl = 1` ve `airCtrl(int, int)` olduğu da denetlenir. Yalnız dört tuş: fan + (2), fan − (3), sol sıcaklık + (4), sol sıcaklık − (5). Her dokunuşta onay sorulur, sonra `airCtrl(tuş, 0)`, 100 ms, `airCtrl(tuş, 1)`; bırakma her durumda gönderilir, olmazsa bir kez daha. GPS hızı 1 km/sa'i aşarsa, GPS 3 sn'den eskiyse ya da yoksa düğmeler pasif (kapalı otoparkta GPS olmayabilir). Gönderilenler, sonuçlar ve öncesi/sonrası klima değerleri `klima-tuslari.csv`'ye (iPad: `/klima-tuslari.csv`).
- 0.7.1'in "Klima +0,5° dene" düğmesi kaldırıldı (eski sonuçlar `/klima-komutu.csv`'de).
- Yeni izinler: konum (yalnız Klima komutu sayfası açıkken GPS dinlenir) ve Bluetooth (Android 8.1'de bağlantı yayınları için).

### 0.7.1 · Klima +0,5° denemesi (K-69, 0.8.0'da kaldırıldı)

Araca giden ilk ve tek komut. Arayüz listesinde, adı yalnız "sıcaklık artır" diyen tek bir parametresiz metot (ör. `tempUp`, `setLeftTempUp`) bulunursa "Klima +0,5° dene" düğmesi açılır; başka bölge ya da komut sözcüğü (fan, mod, arka, koltuk…) olan adlar düğmeyi kapalı tutar. Düğme metodu ve kodunu gösterir, onaylarsan servise bağlanır, servisin bildirdiği arayüz adı metodun arayüzüyle aynıysa tek bir AIDL çağrısı yapar (parametresiz, üretici kodu uygulamada çalışmaz), sonra 3 sn ACTION_AIR_INFO'da yeni sıcaklığı bekler. Sonuç `klima-komutu.csv`'ye (iPad: `/klima-komutu.csv`) ve kayda.

### 0.7.0 · Arayüz dökümü, kapatma testi, servis arayüzü listesi

- **Arayüz dökümü:** com.hcn.autocan penceresi (ör. kapı uyarısı) açıldığında her bileşenin `viewIdResourceName`, `className`, metni, `contentDescription`, `isVisibleToUser`, `boundsInScreen` değerleri ve seçilen kapatma düğmesinin kimliği ve merkez koordinatı `arayuz-dokumu.csv`'ye (iPad: `/arayuz.csv`).
- **Kapatma testi:** "Kapı penceresini kapatmayı dene" düğmesi. Yalnız düğmeye basınca, yalnız kapı uyarısı penceresinde (klima penceresine asla), tek deneme: kapatma düğmesine `ACTION_CLICK`; bulunamazsa `GLOBAL_ACTION_BACK`, o da yalnız hemen önce yapılan kontrolde kapı uyarısı odaktaysa. Uyarı yoksa 15 sn bekler. Kapanma süresi (ms) `pencere-olaylari.csv`'ye.
- **CanService arayüz listesi:** servise bir kez bağlanıp yalnız arayüz adı sorulur (`getInterfaceDescriptor`), metotlar ve TRANSACTION kodları üreticinin APK'sından okunur; `servis-arayuzu.txt` ve rapor. Arayüzün hiçbir metodu çağrılmaz, araca komut gitmez. Klima +0,5° denemesi 0.7.1'de.

### 0.6.0 · Seri port, pencere günlüğü ve servis kontrolü

- **Seri portlar:** `/dev/ttyMT1`, `/dev/ttyMT2`, `/dev/ttyS2`, `/dev/ttyS3` için sahip (UID), grup (GID) ve izinler rapora yazılır. Yalnız `Os.stat()` ve `ls -lZ`; port açılmaz, okunmaz, yazılmaz.
- **Pencere günlüğü (Erişilebilirlik hizmeti):** `com.hcn.autocan`'ın `TYPE_WINDOW_STATE_CHANGED` ve `TYPE_WINDOWS_CHANGED` olayları; zaman, sınıf adı, görünen metin ve contentDescription `pencere-olaylari.csv`'ye (iPad: `/pencereler.csv`) ve açıksa kayda yazılır. Diğer uygulamaların olaylarını görür ama hiçbir yere yazmaz. Hiçbir şeye dokunmaz, tıklamaz. Ayarlar > Erişilebilirlik'ten bir kez açılır.
- **CanService kontrolü:** PackageManager'dan dışa açık mı, izin gerekiyor mu; düğmeyle tek bir `bindService()` denemesi (BIND_AUTO_CREATE yok, hiçbir metot çağrılmaz, hemen `unbindService()`). Sonuç rapora yazılır. Servis çalışıyorsa üreticinin bağlanma/ayrılma kodu bir kez çalışır.

### 0.5.1 · Ek teşhis ve yedekleme

"Paketleri dışa aktar" artık şunları da kopyalar (salt okunur; okunamayan dosya `paketler.csv`'ye "okunamadı" olarak yazılır, işlem durmaz):

- `/system/app/AutoSetting/oat/` ve `/system/priv-app/MtkSettings/oat/` altındaki okunabilen her dosya, klasör yapısıyla `sistem/` altına
- `/data/config/` altındaki okunabilen dosyalar; 5 MB'tan büyükler ve `.apk`'lar atlanır
- `getprop` çıktısı olduğu gibi `getprop.txt` olarak (seri numarası gibi kimlik bilgileri de içerir)
- Yalnız düzenli dosyalar kopyalanır; FIFO, aygıt dosyası ve taranan klasörün dışını gösteren bağlantılar atlanır
- iPad'de `/paketler/teshis.zip`: APK'lar hariç hepsi tek dosyada

### 0.5.0 · Paketleri dışa aktar

- `com.hcn.` ile başlayan tüm paketlerin ve `com.android.settings`'in APK dosyalarını (bölünmüş APK'lar dahil) PackageManager'dan bulur
- Dosyaları sıradan dosya okumasıyla uygulamanın kayıt klasörüne (`kayit/paketler`) kopyalar; root yok, sisteme yazmaz
- `paketler.csv`: her dosyanın kaynak yolu, sürümü, boyutu ve SHA-256 değeri
- iPad'den `http://<ekran>:8080/paketler` adresinden indirilir
- Rapora ve CSV kaydına `Build.DISPLAY`, `Build.FINGERPRINT` ve adında "mcu" geçen sistem değişkenleri eklendi

### 0.4.0 · Canlı araç verisi

- Kapılar ve bagaj, hız, vites, direksiyon açısı, far ve sinyaller, park sensörü, klima için kartlar
- Her kartın durumu: **gri** veri yok · **kehribar** değer var, henüz değişmedi · **yeşil** araçta bir şey yapınca değişti
- Bilinmeyen anahtarlar için tüm sistem değişkenlerini tarar; değişenler ayrı bir listede görünür (vites ve far burada aranıyor)
- **Kayıt**: her değişiklik saatiyle bir CSV dosyasına yazılır; kontak kapanıp açılınca aynı dosyada sürer
- **İşaret**: kontrol listesindeki madde numarasına (3.1, 3.3…) dokununca o an kayda geçer
- iPad'de `http://<ekranın adresi>:8080` canlı sayfa; `/kayit.csv` ve `/kayit.json` ile kayıt indirilir

Okuma: `persist.vendor.cem.*` değişkenleri 250 ms'de bir, tüm değişkenler 2 sn'de bir; Oto Can'ın klima yayınları dinlenir. CAN servisine bağlanmaz, yayın göndermez, ayar yazmaz.

### Ekran raporu (0.2'den beri)

- Model, işlemci, bellek, ekran çözünürlüğü, Android sürümü
- Kurulu uygulamalar; araç (CAN), CarPlay köprüsü ve ana ekran uygulamaları işaretli
- Geliştirici ve animasyon ayarları
- Kısa bir işlemci hız testi

Rapor, aynı hotspot'a bağlı iPad'den `http://<ekranın adresi>:8080/rapor` ile açılabilir; uygulama adresi ve QR kodu gösterir.

## İmza anahtarı

Keşif APK'ları `kokpit` anahtarıyla imzalanır. Anahtar depoda değil, GitHub'da gizli değişken olarak saklanır: `KOKPIT_KEYSTORE_B64` (anahtar dosyasının base64'ü) ve `KOKPIT_KEYSTORE_PASSWORD`. Derlerken dosyayı `keys/kokpit.jks` olarak açıp parolayı ortam değişkeniyle ver.

Ana Ekran kendi `anaekran` anahtarıyla imzalanır (K-91): `keys/anaekran.jks`, parola `ANAEKRAN_KEYSTORE_PASSWORD` (yol `ANAEKRAN_KEYSTORE` ile de verilebilir). Anahtar depoda değil; kullanıcıda saklanır. Her Ana Ekran sürümü aynı anahtarla imzalanmalı.

## Klasörler

- `anaekran/` — Kokpit Ana Ekran (Kotlin, ek kütüphane yok; yazı tipleri Google Fonts, OFL)
- `kesif/` — Kokpit Keşif (Kotlin, ek kütüphane yok, yalnızca QR için ZXing)
- `dist/` — indirme bağlantılarının verdiği `kokpit-kesif.apk`, `kokpit-anaekran.apk` ve sürüm adlı arşiv kopyaları
