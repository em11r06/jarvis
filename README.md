# Jarvis: Türkçe kişisel sesli asistan (Android)

Jarvis seninle arkadaş gibi Türkçe sohbet eder, sesli cevap verir, konuşmaları hatırlar,
internette araştırma yapar ve telefonda komutlarını yerine getirir. Arka planda
"Jarvis" diye seslenince uyanır.

- Kotlin + Jetpack Compose, Material 3, koyu "Iron Man" teması
- Yapay zeka: Gemini (varsayılan, `gemini-3.8-flash`) veya Claude (`claude-haiku-4-5`), tool calling ile
- Ses tanıma: Android SpeechRecognizer (tr-TR)
- Uyandırma: ücretsiz ve çevrimdışı Vosk (anahtar gerekmez) ya da isteğe bağlı Picovoice Porcupine
- Ses: telefondaki tüm TTS sesleri veya ElevenLabs
- Hedef: Samsung S25+, One UI 8.5 / Android 16 (compileSdk/targetSdk 36), root gerekmez

---

## 1. Telefona kurulum (bilgisayar gerekmez)

1. Telefonda tarayıcıdan GitHub'a gir: `github.com/em11r06/jarvis` > **Releases** > **latest**.
2. **Jarvis.apk** dosyasını indir ve aç.
3. "Bilinmeyen uygulamalar" uyarısı çıkarsa: tarayıcıya (Chrome / Samsung Internet) **"Bu kaynaktan izin ver"** de.
4. **Play Protect "Uygulama, cihazınızı korumak için engellendi" derse:** Jarvis erişilebilirlik ve SMS
   izni istediği için Google, Play Store dışından gelen bu tür uygulamaları otomatik engelliyor. Kurulum için geçici olarak kapat:
   1. **Play Store** > sağ üstte profil resmi > **Play Protect** > sağ üstte **⚙ (ayarlar)**
   2. **"Uygulamaları Play Protect ile tara"** seçeneğini kapat
   3. APK'yı tekrar aç ve kur
   4. İstersen sonra aynı yerden tekrar aç (Jarvis'i tekrar işaretleyebilir; "Yine de yükle/Tut" de)
   5. Hâlâ engelleniyorsa Samsung'un **Otomatik Engelleyici**'sini kapat: Ayarlar > Güvenlik ve gizlilik > Otomatik Engelleyici
5. Kur ve Jarvis'i aç. Kurulum sihirbazı başlar; adımları sırayla takip et.

Her push'ta yeni APK otomatik derlenir ve aynı "latest" sayfasına konur.

**Otomatik güncelleme:** Jarvis açılınca (en fazla 3 saatte bir) yeni sürüm var mı diye bakar,
varsa arka planda indirir ve "Yeni sürüm hazır" diye sorar. **Güncelle**'ye dokunman yeterli.
Sesli olarak "Jarvis, kendini güncelle" de diyebilirsin. Ayarlar > Güncelleme bölümünden elle de denetlenebilir.
Bunun için depo **public** olmalı (kodda hiçbir anahtar yok) ve Jarvis'e "bilinmeyen uygulamaları yükleme"
izni verilmeli (sihirbaz ister).

## 2. API anahtarları (nereden alınır?)

Anahtarlar koda yazılmaz. Jarvis'te **Ayarlar** (sağ üstteki dişli) ekranına yapıştırılır ve
sadece telefonda saklanır.

| Anahtar | Nereden | Ücret | Zorunlu mu |
|---|---|---|---|
| **Gemini** | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) > Google hesabınla gir > **Create API key** > kopyala | Ücretsiz katman var | Evet (ya da Claude) |
| **Picovoice AccessKey** | [console.picovoice.ai](https://console.picovoice.ai) (şirket e-postası istiyor) | Ücretsiz | Hayır. Anahtar yoksa ücretsiz Vosk motoru kullanılır |
| **ElevenLabs** | [elevenlabs.io](https://elevenlabs.io) > giriş yap > sol altta profil > **API Keys** > Create | Ücretsiz deneme kotası var | Hayır (isteğe bağlı ses) |
| **Claude** | [console.anthropic.com](https://console.anthropic.com) > **API Keys** | Ücretli | Hayır |

## 3. Telefonda açılacak ayarlar

Sihirbaz bunları sırayla ister. Atladıysan Ayarlar > **Kurulum sihirbazı** ile tekrar açabilirsin.

1. **Mikrofon, bildirim, rehber, telefon, SMS izinleri**: sihirbazda "İzin ver"e bas.
2. **Diğer uygulamaların üzerinde göster**: listede Jarvis'i bul ve aç. (Arka plandan uygulama açabilmek için.)
3. **Pil optimizasyonu**: "İzin ver" de. Ayrıca Samsung'da:
   Ayarlar > Pil > Arka plan kullanım sınırları > **Hiç uyumayan uygulamalar** > Jarvis'i ekle.
4. **Kısıtlı ayarlara izin ver** (Android 16, Play Store dışından kurulan uygulamalar için şart):
   1. Ayarlar > Uygulamalar > **Jarvis** (sihirbazdaki düğme bu ekranı açar)
   2. Sağ üstteki **⋮** (üç nokta)
   3. **Kısıtlı ayarlara izin ver** > parmak izi / PIN ile onayla
   4. Menüde bu seçenek yoksa: önce aşağıdaki erişilebilirlik adımında Jarvis'i açmayı dene.
      "Kısıtlı ayar" uyarısı çıktıktan sonra seçenek görünür.
5. **Erişilebilirlik**: Ayarlar > Erişilebilirlik > **Yüklü uygulamalar** > Jarvis > Aç.
6. **Ses tanıma**: Türkçe tanıma için Google uygulaması güncel olmalı. Çevrimdışı tanıma istersen:
   Ayarlar > Genel yönetim > Klavye listesi ve varsayılan > Google sesle yazma > Çevrimdışı konuşma tanıma > Türkçe'yi indir.
7. **Varsayılan asistan ve yan tuş (isteğe bağlı)**:
   - Ayarlar > Uygulamalar > Varsayılan uygulamaları seç > **Dijital asistan uygulaması** > Jarvis
   - Ayarlar > Gelişmiş özellikler > **Yan tuş** > Basılı tut > **Dijital asistan**
   - Artık yan tuşa uzun basınca Jarvis açılır ve hemen dinlemeye başlar.
8. **Sesimi tanıt** (önerilir): Jarvis Ayarlar > **Sesimi tanı** > 5 kez "Jarvis" de. Bundan sonra Jarvis sadece
   senin sesine uyanır; şarkılardaki benzer seslere ve başkalarına tepki vermez. Seni tanımazsa "Seçicilik"i düşür.
9. **Uyandırma**: Jarvis Ayarlar > **Arka planda dinle**'yi aç (anahtar gerekmez). İlk açılışta ses modeli telefona kopyalanır, birkaç saniye sürer.
   Bildirimde "Jarvis dinliyor" görünür; bildirimdeki **Kapat** ile durdurabilirsin.

### Telefon yeniden başlayınca
Android 15/16, telefon açılırken mikrofon servisinin kendiliğinden başlamasına izin vermiyor.
Jarvis önce erişilebilirlik servisi üzerinden başlatmayı dener. Olmazsa
**"Jarvis'i başlatmak için dokun"** bildirimi gelir; bir kez dokunman yeterli.

## 4. GitHub Secrets (sabit imza anahtarı)

Güncellemeler üzerine kurulabilsin diye her APK aynı anahtarla imzalanmalı. Bunun için
depoya 4 secret ekle: GitHub'da depo > **Settings** > **Secrets and variables** > **Actions** > **New repository secret**.

| Secret adı | Değeri |
|---|---|
| `KEYSTORE_BASE64` | Anahtar dosyasının base64 hâli (aşağıda) |
| `KEYSTORE_PASSWORD` | Anahtar deposu şifresi |
| `KEY_ALIAS` | Anahtar adı, ör. `jarvis` |
| `KEY_PASSWORD` | Anahtar şifresi (genelde depo şifresiyle aynı) |

Anahtarı bilgisayarda bir kez oluştur (Java yüklü olmalı):

```bash
keytool -genkeypair -v -keystore jarvis.jks -storetype PKCS12 -alias jarvis \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Jarvis, C=TR"
# Şifre sorar. Bu şifre KEYSTORE_PASSWORD ve KEY_PASSWORD olacak.

base64 -w0 jarvis.jks > jarvis.b64     # macOS: base64 -i jarvis.jks -o jarvis.b64
# jarvis.b64 dosyasının içeriğini KEYSTORE_BASE64 secret'ına yapıştır.
```

`jarvis.jks` dosyasını ve şifreyi güvenli bir yerde sakla. Kaybedersen güncelleme kuramazsın;
uygulamayı silip yeniden kurman gerekir.

**Secret eklemeden önce ne olur?** İş akışı ilk derlemede geçici bir anahtar üretir ve
bunu GitHub Actions önbelleğinde tutar (depoya girmez), yani derlemeler arasında imza aynı kalır. Ancak
önbellek 7 gün kullanılmazsa silinebilir. Bu yüzden secret'ları ekle. Secret ekledikten
sonraki ilk güncellemede imza değişeceği için Jarvis'i **bir kez kaldırıp** yeniden kurman gerekir
(geçmiş ve ayarlar silinir).

## 5. Kullanım örnekleri

- "Jarvis... nasılsın?" (sohbet)
- "Yarın İstanbul'da hava nasıl olacak?" (web'de arar)
- "Tarkan'ın Kuzu Kuzu'sunu aç, sesi de fulle" (iki işi sırayla yapar)
- "Şarkıyı durdur" / "sonraki şarkı"
- "Kadıköy'e yol tarifi ver"
- "Annemi ara" (önce onay ister)
- "Ahmet'e akşam geliyorum diye mesaj at" (mesajı okur, onay ister)
- "Sabah yedi buçuğa alarm kur", "on dakikalık zamanlayıcı kur"
- "Feneri aç", "pil kaç?"
- "WhatsApp'tan Ayşe'ye 'geliyorum' yaz" (ekranı okuyup adım adım yapar)
- "Ekran görüntüsü al", "ekranı kilitle", "telefonu yeniden başlat"

Cevaptan sonra birkaç saniye (Ayarlar > Takip dinleme süresi) "Jarvis" demeden devam
edebilirsin. "Teşekkürler", "sağ ol", "bu kadar" gibi bir sözle oturumu bitirebilirsin ("tamam" bilerek yok, çünkü onay cevabı olabilir).

## 6. Proje yapısı (programlamaya yeni başlayanlar için)

```
app/src/main/java/com/jarvis/assistant/
├── JarvisApp.kt               Uygulama başlarken ortak nesneleri kurar
├── MainActivity.kt            Ekranlar arası geçiş
├── ai/                        Yapay zeka
│   ├── Types.kt               AiProvider arayüzü, mesaj tipleri
│   ├── GeminiProvider.kt      Gemini REST (thoughtSignature korunur)
│   ├── ClaudeProvider.kt      Claude Messages API
│   └── Agent.kt               Sistem promptu, araç döngüsü, geçmiş özetleme
├── core/AssistantController   Dinle > düşün > konuş > takip dinle akışı
├── speech/                    Ses tanıma (SpeechListener), konuşma (Speaker), ses listesi
├── tools/                     Jarvis'in araçları (her biri ayrı sınıf)
│   └── ToolRegistry.kt        Araç kayıt listesi
├── accessibility/             Ekranı okuma ve dokunma servisi
├── wake/                      Porcupine uyandırma servisi, açılış alıcısı
├── data/                      Ayarlar ve konuşma geçmişi
└── ui/                        Compose ekranları
```

**Yeni araç eklemek:** `tools/` içine `Tool` arayüzünü uygulayan bir sınıf yaz
(`name`, `description`, `parameters`, `run`), sonra `ToolRegistry.all` listesine ekle. Bu kadar.

## 7. Verdiğim kararlar (notlar)

Kararsız kaldığım yerlerde şunları seçtim:

- **Derleme doğrulaması:** Hazırladığım bulut ortamında Google'ın Maven deposu (dl.google.com) engelliydi, bu yüzden
  her aşamayı GitHub Actions'ta derleyerek kontrol ettim. Derleme iş akışını bu yüzden en başta ekledim.
- **minSdk 30 (Android 11):** Telefonun Android 16 olduğu için eski sürüm desteğine gerek görmedim.
- **ses_ayarla "kis":** "Sesi kıs" ile "azalt" ayrı yazıldığı için `kis` = sessize al (0), `azalt` = birkaç kademe düşür.
- **Onay gerektiren işler:** Sistem promptu onay istemeyi söylüyor. Ayrıca güvenlik için `arama_yap`, `sms_gonder` ve
  `yeniden_baslat` araçları `onaylandi=true` olmadan çalışmıyor.
- **Gemini düşünme seviyesi:** Sesli asistanda hız önemli olduğu için `thinkingLevel: minimal`. Model desteklemezse sırayla `low` ve ayarsız hâli denenir.
- **Geçmiş özetleme:** 40 adımı geçince eski kısım özetlenir, son 14 adım aynen kalır. Özet sistem promptuna eklenir.
- **Anahtarların saklanması:** Uygulamaya özel SharedPreferences (başka uygulamalar okuyamaz). EncryptedSharedPreferences kütüphanesi
  Google tarafından kullanımdan kaldırıldığı için kullanılmadı. `allowBackup=false` ile yedeklere de girmez.
- **ElevenLabs modeli:** Varsayılan `eleven_flash_v2_5` (hızlı, Türkçe destekli). Ses ekranından "Kaliteli" (`eleven_multilingual_v2`) seçilebilir.
  Cevap cümlelere bölünür; ilk cümle iner inmez çalar, o çalarken sonraki iner. Hata/internet yoksa seçili Android sesine döner.
- **Doğal ses:** ElevenLabs'e stability 0.4, style 0.2 ve önceki/sonraki cümle bilgisi gönderilir (akıcı tonlama).
- **Uyandırma motoru:** Picovoice şirket e-postası istediği için varsayılan motor **Vosk** (ücretsiz, çevrimdışı, Apache lisansı).
  Küçük İngilizce model (~40 MB) derleme sırasında indirilip APK'ya konur; tanıyıcı sadece "jarvis" kelimesini arayacak şekilde kısıtlanır.
  Porcupine'den biraz daha fazla pil harcar. Yanlış uyanmayı azaltmak için kelime güven puanı kontrol edilir (müzik çalarken daha sıkı)
  ve kullanıcı sesini tanıttıysa Vosk ses izi modeliyle (vosk-model-spk-0.4) konuşanın o olup olmadığına bakılır.
  Eşik, kayıt sırasındaki örneklerin birbirine benzerliğinden kişiye göre hesaplanır. Picovoice anahtarı girilirse otomatik olarak Porcupine (hassasiyet 0.65) kullanılır.
- **Arka planda ses varken:** Uyandırma mikrofonu `VOICE_COMMUNICATION` kaynağıyla, yankı engelleme (AEC) ve gürültü azaltma
  açık okunur; telefonun kendi çaldığı müzik/video büyük ölçüde mikrofondan çıkarılır. "Jarvis" deyince diğer uygulamaların
  sesi oturum boyunca kısılır (audio focus, duck) ve konuşma bitince geri gelir.
- **Spotify:** `MEDIA_PLAY_FROM_SEARCH` intent'i. Spotify bazen sadece aramayı açıp çalmıyor; 3,5 sn sonra müzik çalmıyorsa "oynat" tuşu gönderiliyor.
- **Ekran görüntüsü:** Erişilebilirlik servisinin `GLOBAL_ACTION_TAKE_SCREENSHOT` işlemi (sistem gibi galeriye kaydeder).
- **Dokun/yaz/kaydır sonrası:** Araç, işlemden sonra ekranın yeni hâlini otomatik döndürür; yapay zeka bir sonraki adımı daha az turda seçer.
- **Varsayılan asistan:** Tam bir VoiceInteractionService yerine `ACTION_ASSIST` destekleyen ekran kullanıldı (daha basit, yan tuş için yeterli).
- **Release:** Her dalda her push'ta "latest" sürümü silinip yeniden oluşturulur. Sürüm numarası = Actions çalışma numarası (versionCode); Jarvis güncellemeyi buna göre anlar.
- **İmza:** İmza anahtarını depoya koymadım; sadece Secrets'tan okunur. Secret yoksa önbellekteki debug anahtarı kullanılır (yukarıya bak).

## 8. Bilinen sınırlamalar

- DuckDuckGo HTML sayfası çok sık istekte geçici olarak engelleyebilir; o zaman arama birkaç dakika sonra tekrar çalışır.
- Ekran kontrolü, uygulamanın erişilebilirlik bilgisi verdiği kadar iyi çalışır (bazı oyunlar ve özel çizimli ekranlar okunamaz).
- Samsung güç menüsü tasarımı One UI sürümüyle değişirse `yeniden_baslat` düğmeyi bulamayabilir. Araç "Yeniden başlat", "Restart" ve "Reboot" yazılarını arar.
- Gemini ücretsiz katmanında model başına **günlük istek sınırı** var (ör. 20/gün) ve her araç kullanımı ayrı istek sayılır.
  Jarvis seçili modelin kotası dolunca sırayla `gemini-flash-latest`, `gemini-flash-lite-latest`, `gemini-2.5-flash`,
  `gemini-2.5-flash-lite` modellerine geçer (her birinin ayrı kotası var). Kotası dolan model, Google'ın kotayı sıfırladığı ana kadar (günlük kota: Pasifik saatiyle gece yarısı = TR 10:00/11:00;
  dakikalık sınır: Google'ın bildirdiği bekleme süresi) atlanır ve sonra Jarvis kendiliğinden yine seçili modeli kullanır
  (bilgi telefonda saklanır; Ayarlar'da hangi modelin kullanıldığı görünür). Hepsi dolarsa kota ertesi gün yenilenir;
  sınırsız kullanım için AI Studio'da faturalandırmayı açmak ya da Claude'a geçmek gerekir.
