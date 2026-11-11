# Kokpit

Renault Mégane IV'teki dikey araç ekranı için kendi arayüzümüz.

## ⬇️ Kokpit Keşif'i indir

**[▶ Kokpit Keşif'i indir (APK)](https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-kesif.apk)**

Sürüm: 0.3.0 · 82 KB · önceki sürümün üzerine kurulur

**0.3.0 için:** Kapıları tek tek açıp kapat, motoru çalıştır, klimayı değiştir; sonra raporu iPad'den gönder. Uygulama yalnızca okur; araca hiçbir komut göndermez.

Ekranın tarayıcısında bu sayfayı açıp yukarıdaki bağlantıya dokun. İnince "Yükle"ye dokun.
İlk seferde "Bilinmeyen uygulamalar" izni istenirse tarayıcıya bir kez izin ver.

## Kokpit Keşif nedir?

Ekranın kimlik kartını çıkaran küçük bir uygulama. **Yalnızca okur, hiçbir ayarı değiştirmez.**

- Model, işlemci, bellek, ekran çözünürlüğü, Android sürümü
- Kurulu uygulamalar; araç (CAN), CarPlay köprüsü ve ana ekran uygulamaları işaretli
- Geliştirici ve animasyon ayarları
- Kısa bir işlemci hız testi

Rapor, aynı hotspot'a bağlı iPad'den `http://<ekranın adresi>:8080` ile açılabilir; uygulama adresi ve QR kodu gösterir.

## Klasörler

- `kesif/` — Kokpit Keşif (Kotlin, ek kütüphane yok, yalnızca QR için ZXing)
