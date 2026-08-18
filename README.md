# CloudStream Turkce Eklenti Deposu (Repository)

Bu depoda populer Turkce film ve dizi siteleri icin CloudStream 3 eklentileri bulunmaktadir:

- **HDFilmCehennemi** (`https://www.hdfilmcehennemi.nl/`)
- **FullHDFilmizlesene** (`https://www.fullhdfilmizlesene.now/`)
- **FilmMakinesi** (`https://filmmakinesi.to/`)

---

## Kurulum ve CloudStream Uygulamasina Ekleme

1. Bu projeyi GitHub hesabinizda olusturduguniz yeni bir depoya yukleyin (push).
2. Repository ayarlarindan **Settings -> Actions -> General -> Workflow permissions -> Read and write permissions** secenegini isaretleyip kaydedin.
3. GitHub Actions otomatik calisip projeyi derleyecek ve `builds` dalina `plugins.json` olusturacaktir.
4. CloudStream uygulamasinda **Ayarlar -> Eklentiler -> Depo Ekle** kismina su linki girin:
   ```text
   https://raw.githubusercontent.com/<KULLANICI_ADINIZ>/<REPO_ADINIZ>/builds/plugins.json
   ```
