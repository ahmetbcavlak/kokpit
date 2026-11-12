# Kokpit

Renault Mégane IV'teki dikey araç ekranı için kendi arayüzümüz.

## ⬇️ Kokpit Keşif'i indir

**[▶ Kokpit Keşif'i indir (APK)](https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-kesif.apk)**

Sürüm: 0.4.0 · 102 KB · **önce eski Keşif'i kaldır**

0.4.0 yeni bir imza anahtarıyla imzalı; 0.2.0'ın üzerine kurulmaz. Bir kez:

1. Ekranda Ayarlar > Uygulamalar ve bildirimler > Kokpit Keşif > **Kaldır**. Kaybolan bir şey yok.
2. Ekranın tarayıcısında bu sayfayı açıp yukarıdaki bağlantıya dokun. İnince "Yükle"ye dokun.
   İlk seferde "Bilinmeyen uygulamalar" izni istenirse tarayıcıya bir kez izin ver.

Önceki sürümler `dist/` klasöründe sürüm adıyla duruyor.

## Kokpit Keşif nedir?

Ekranı ve araçtan gelen veriyi tanımak için küçük bir uygulama. **Yalnızca okur, hiçbir ayarı değiştirmez, araca hiçbir şey göndermez.**

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
