# Babayaro - Cloudstream Türkçe Eklenti Deposu 🎬

Cloudstream için yüksek performanslı, kararlı ve otomatik oynatma destekli Türkçe film ve dizi eklentileri deposu.

---

## 📦 İçerdiği Eklentiler

| Eklenti | Tür | Açıklama |
| :--- | :--- | :--- |
| **HDFilmCehennemi** | Film & Dizi | Son eklenen ve popüler filmler, hızlı m3u8 akışı ve dahili VTT altyazı desteği. |
| **Dizipal** | Film & Dizi | Next.js/PBKDF2 şifre çözücü motoru, tam sezon/bölüm listesi, 1080p doğrudan HLS akışı. |
| **Dizibox** | Yabancı Dizi | Güncel yabancı diziler, MolyStream/Sheila 1080p doğrudan HLS akış çözücü. |

---

## 🚀 Cloudstream'e Repo Nasıl Eklenir?

1. Cloudstream uygulamasını açın.
2. **Ayarlar (Settings)** > **Eklentiler (Extensions)** > **Depolar (Repositories)** bölümüne gidin.
3. **Depo Ekle (Add Repository)** butonuna tıklayın:
   * **Depo Adı:** `babayaro`
   * **Depo URL:** 
     ```text
     https://raw.githubusercontent.com/<GITHUB_KULLANICI_ADINIZ>/babayaro/builds/plugins.json
     ```
4. Depoyu ekledikten sonra eklentiler listelenir; dilediğinizi tek tıkla yükleyip izlemeye başlayabilirsiniz!

---

## 🛠️ Özellikler

* **Otomatik Şifre Çözücü:** Dizipal ve MolyStream şifreli akışlarını cihazınızda donanım hızlandırmalı yerel Kotlin motoruyla anında çözer.
* **Doğrudan HLS (m3u8):** Reklamlı ve yavaş web oynatıcılar yerine doğrudan m3u8 video akışını Cloudstream dahili oynatıcısına iletir.
* **Altyazı ve Dublaj:** Çoklu dil ve harici VTT altyazı parçalarını otomatik algılar.
