# Kokpit

Renault Mégane IV'teki dikey araç ekranı için kendi arayüzümüz.

## ⬇️ Kokpit Keşif'i indir

**[▶ Kokpit Keşif'i indir (APK)](https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-kesif.apk)**

Sürüm: 0.7.1 · 145 KB · 0.4 ve sonrasının üzerine kurulur; 0.2.0 yüklüyse **önce onu kaldır**

0.4.0'dan beri yeni bir imza anahtarı kullanılıyor; 0.2.0'ın üzerine kurulmaz. Ekranda 0.2.0 varsa bir kez:

1. Ekranda Ayarlar > Uygulamalar ve bildirimler > Kokpit Keşif > **Kaldır**. Kaybolan bir şey yok.
2. Ekranın tarayıcısında bu sayfayı açıp yukarıdaki bağlantıya dokun. İnince "Yükle"ye dokun.
   İlk seferde "Bilinmeyen uygulamalar" izni istenirse tarayıcıya bir kez izin ver.

Önceki sürümler `dist/` klasöründe sürüm adıyla duruyor.

## Kokpit Keşif nedir?

Ekranı ve araçtan gelen veriyi tanımak için küçük bir uygulama. **Yalnızca okur, hiçbir ayarı değiştirmez, araca hiçbir şey göndermez.**

### 0.7.1 · Klima +0,5° denemesi (K-69)

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

APK'lar `kokpit` anahtarıyla imzalanır. Anahtar depoda değil, GitHub'da gizli değişken olarak saklanır: `KOKPIT_KEYSTORE_B64` (anahtar dosyasının base64'ü) ve `KOKPIT_KEYSTORE_PASSWORD`. Derlerken dosyayı `keys/kokpit.jks` olarak açıp parolayı ortam değişkeniyle ver.

## Klasörler

- `kesif/` — Kokpit Keşif (Kotlin, ek kütüphane yok, yalnızca QR için ZXing)
- `dist/` — indirme bağlantısının verdiği `kokpit-kesif.apk` ve sürüm adlı arşiv kopyaları
