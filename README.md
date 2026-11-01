# Dağ Mesh

İnternet ve şebeke olmadan, yakındaki telefonlarla haberleşmeyi sağlayan Android uygulaması.

## Nasıl çalışır

- **Bağlantı:** Google Nearby Connections, Bluetooth, BLE, Wi‑Fi Direct ve hotspot bağlantılarını kendisi seçer ve birleştirir.
- **Zincirleme aktarım (mesh):** Alıcı doğrudan menzilde değilse mesaj aradaki Dağ Mesh telefonları üzerinden atlayarak gider (en fazla 6 atlama).
- **Sakla ve ilet:** Kimse menzilde değilse mesaj telefonda bekler. Yeni bir telefona bağlanınca otomatik yeniden gönderilir (48 saat boyunca).
- **Onay:** Saat simgesi = sırada bekliyor, tek tik = ağa verildi, çift tik = karşıya ulaştı.

## APK'yı indirme

Her `main` güncellemesinde GitHub Actions APK'yı derler. Dosya **Releases** sayfasına yüklenir.
Her sürüm aynı imzayla çıktığı için yeni APK eskisinin üstüne kurulur.

## Yol haritası

1. ✅ Yakındakileri bulma (Bluetooth + Wi‑Fi Direct + Wi‑Fi Aware), metin mesajı, zincirleme aktarım, sakla‑ilet
2. ✅ Sesli mesaj, fotoğraf, video, dosya (parçalı aktarım, eksik parça isteme), sesli arama (ADPCM, yankı engelleme)
3. Uçtan uca şifreleme, QR ile kişi ekleme
4. SOS acil durum yayını, çevrimdışı harita ve konum paylaşımı
5. Yerel sosyal akış

## Proje yapısı

```
app/src/main/java/com/dagmesh/app/
├── data/   Room veritabanı, kimlik
├── mesh/   Ağ motoru (MeshManager), paket biçimi, arka plan hizmeti, bildirimler
└── ui/     Jetpack Compose ekranları (karşılama, radar, sohbet)
```
