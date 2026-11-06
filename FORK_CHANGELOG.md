# Fossify Messages — Security-Hardened Fork Changelog

**Base Upstream Version:** `FossifyOrg/Messages` `v1.10.2` (`VERSION_CODE = 26`)  
**Source Directory:** `c:\Users\ben\Desktop\Fossify Messages\Messages-main`

Bu doküman, orijinal **Fossify Messages** kaynak kodu üzerinde saldırı yüzeyini (attack surface) küçültmek, zero-click MMS/medya ayrıştırma risklerini ortadan kaldırmak, metin şekillendirme (HarfBuzz/Minikin/FreeType) ve font kaynaklı zafiyetleri engellemek, oltalama (phishing) bağlantılarının kazara açılmasını önlemek ve uygulama izin/bileşen sınırlarını sıkılaştırmak amacıyla yapılan tüm değişiklikleri detaylandırır.

---

## 1. MMS İşlemenin Tamamen Kapatılması (Zero-Click MMS Koruması)

### Güvenlik Gerekçesi
Gelen MMS mesajları (`WAP_PUSH_DELIVER`), üçüncü parti `mmslib` (`android-smsmms`) kütüphanesi içerisindeki binary `PduParser` ile ayrıştırılır, ardından MMSC sunucusuna otomatik HTTP bağlantısı kurularak medya ekleri (`image/*`, `video/*`, `audio/*`, `text/x-vcard`, `application/smil`) indirilir ve `Glide`, `ez-vcard`, `SmilXmlParser` veya sistem medya codec'leri tarafından işlenir. Bu katman, SMS/MMS uygulamalarındaki en büyük uzaktan kod çalıştırma (RCE) ve zero-click saldırı yüzeyidir.

### Yapılan Değişiklikler
- **Yeni Ayar (`Block MMS completely (do not parse or download)`):**
  - `Settings -> Security` altına `settings_block_mms` anahtarı eklendi (`Config.blockMms`, anahtar: `block_mms`).
- **Ham WAP Push PDU Ayrıştırmasının Engellenmesi (`WapPushReceiver.kt`):**
  - `com.android.mms.transaction.PushReceiver` yerine özel `org.fossify.messages.receivers.WapPushReceiver` oluşturuldu.
  - `config.blockMms` etkin olduğunda, gelen `WAP_PUSH_DELIVER` yayınındaki ham `data` (`byte[]` MMS PDU) **kesinlikle okunmaz ve `PduParser` çağrılmaz**; MMSC sunucusuna hiçbir ağ/indirme isteği gönderilmez.
  - Yalnızca Android Telephony framework'ünün (`WapPushOverSms`) intent'e eklediği `address` (veya `sender`) bilgisi alınır, numara engelleme kontrollerinden geçirilir ve sohbet geçmişine doğrudan **`(MMS engellendi)`** gövdesiyle standart bir mesaj kaydı düşülerek bildirim gösterilir.
- **Kayıtlı MMS Parçalarının ve SMIL XML İçeriğinin Ayrıştırılmasının Engellenmesi (`Context.kt`, `MmsReceiver.kt`, `ThreadAdapter.kt`):**
  - `Context.getMMS()` içinde `config.blockMms` açıkken `getMmsAttachment()` çağrılmaz (`content://mms/part` okunmaz, `parseAttachmentNames` XML ayrıştırıcısı çalıştırılmaz); mesaj gövdesi `(MMS engellendi)` ve `attachment = null` olarak döndürülür.
  - `MmsReceiver.isAddressBlocked()` ve `MmsReceiver.onMessageReceived()` içine erken çıkış (`if (context.config.blockMms) return`) eklendi.
  - `ThreadAdapter.setupView()` içerisinde `config.blockMms` açıkken hiçbir medya/vCard/ses/dosya eki görünümü oluşturulmaz.
- **Giden MMS ve Ek Gönderiminin Devre Dışı Bırakılması (`ThreadActivity.kt`, `Messaging.kt`):**
  - `config.blockMms` açıkken mesaj yazma ekranındaki ataş (ek ekleme) butonu gizlenir (`threadAddAttachment.beVisibleIf(!config.blockMms)`) ve `addAttachment()` çağrıları reddedilir.
  - `Messaging.getSendMessageSettings()` ve `sendMessageCompat()` içerisinde uzun mesajların veya grup mesajlarının MMS'e dönüştürülmesi engellenir.

---

## 2. "Roboto Only" Karakter Beyaz Listesi ve Font Saldırı Yüzeyi Koruması

### Güvenlik Gerekçesi
Google Messages ve Fossify Messages içerisindeki standart *"Use simple characters"* ayarı yalnızca **giden** mesajlardaki aksanlı harfleri dönüştürür; **gelen** mesajlar üzerinde hiçbir güvenlik etkisi yoktur.  
Gelen bir SMS içerisinde yer alan karmaşık Unicode dizileri, emojiler (`NotoColorEmoji.ttf` içindeki `CBDT`/`CBLC` bitmap veya `COLRv1` vektör tabloları), sağdan-sola (Bidi override `U+202A..U+202E`) kontrol karakterleri, aşırı birleştirici işaretler (Zalgo / combining marks) veya egzotik alfabeler, Android'in C/C++ tabanlı `HarfBuzz`, `Minikin`, `FreeType` ve `Skia` motorlarında bellek bozulması, çökme (DoS) veya görsel sahtecilik (homograph spoofing) zafiyetlerini tetikleyebilir.

### Yapılan Değişiklikler
- **Yeni Ayar (`Roboto only (allow only standard keyboard characters)`):**
  - `Settings -> Security` altına varsayılan olarak **açık (`true`)** gelen `settings_sanitize_message_text` anahtarı eklendi (`Config.sanitizeMessageText`, anahtar: `sanitize_message_text`).
- **Katı Beyaz Liste (Allowlist) Karakter Filtresi (`String.sanitizeForDisplay()`):**
  - Gelen ve gösterilen metinler önce `Normalizer.Form.NFC` formuna normalize edilir (böylece ayrık birleştirici aksanlar tekil standart harfe indirgenir ve tüm harici combining mark'lar elenir).
  - Yalnızca `Roboto` sistem fontunun doğrudan desteklediği standart klavye karakterlerine izin verilir:
    - Temel boşluk karakterleri: `0x20` (boşluk), `\n`, `\r`, `\t`.
    - Standart görünür ASCII (`0x21..0x7E`): İngilizce `A-Z`, `a-z`, `0-9` ve standart klavye noktalama işaretleri.
    - Standart Latin / Türkçe / Almanca harfler (`0x00C0..0x024F` aralığındaki harfler ve `U+1E9E` `ẞ`): Türkçe (`Ç, ç, Ğ, ğ, İ, ı, Ö, ö, Ş, ş, Ü, ü`) ve Almanca (`Ä, ä, Ö, ö, Ü, ü, ß, ẞ`) dahil standart Avrupa Latin harfleri.
    - Standart klavye para birimi ve tipografi sembolleri: `₺, €, £, ¥, §, °, ±, «, », ×, ÷, –, —, ‘, ’, ‚, “, ”, „, …`.
  - **Engellenenler:** Tüm emojiler, tüm birleştirici işaretler (combining marks / Zalgo), Bidi yön değiştirme/override kontrolleri, sıfır genişlikli (zero-width) görünmez karakterler ve Latin dışı tüm alfabeler (Kiril, Yunan, Arap, İbrani, Güney Asya, Doğu Asya vb.).
- **Davranış ve `(Unsupported characters removed)` Gösterimi:**
  - Filtreleme veritabanındaki ham mesajı bozmadan **görüntüleme (render) katmanında** (`ThreadAdapter`, `BaseConversationsAdapter`, `SearchResultsAdapter`, `NotificationHelper`, `SelectTextDialog`) uygulanır.
  - Bir mesaj hem normal metin hem de desteklenmeyen karakter (ör. emoji) içeriyorsa yalnızca desteklenmeyen karakterler süzülür, mesajın geri kalanı normal görünür.
  - Eğer mesaj **yalnızca** desteklenmeyen karakterlerden oluşuyorsa (ör. sadece `"👍"` gönderildiyse), mesajın kaybolmaması ve sohbet geçmişinin oluşması için ekranda **`(Unsupported characters removed)`** ifadesi gösterilir.
- **Yerleşik `Roboto` (`Typeface.DEFAULT`) Zorlaması (`ThreadAdapter.kt`, `BaseConversationsAdapter.kt`):**
  - Ayar açıkken mesaj balonlarında ve konuşma listesinde `Typeface.DEFAULT` zorunlu kılınarak özel/harici font yüklenmesi engellenir.

---

## 3. Bağlantı Önizlemeleri ve Otomatik Tıklanabilir Linklerin Kaldırılması (Standart)

### Güvenlik Gerekçesi
Mesaj içerisindeki web veya e-posta bağlantılarının otomatik olarak mavi/tıklanabilir (`autoLink`) yapılması, kullanıcının kaydırma yaparken kazara bağlantıya dokunarak tarayıcıyı tetiklemesine (oltalama / drive-by exploit) yol açabilir.

### Yapılan Değişiklikler
- **Bağlantı Önizlemeleri (Link Previews):**
  - Tüm kaynak kod denetlendi; uygulamada URL açıp OpenGraph/HTML önizlemesi indiren hiçbir kod bulunmadığı doğrulandı.
- **Otomatik Linklerin ve Etkileşimin Kaldırılması (`item_message.xml`, `ThreadAdapter.kt`):**
  - `res/layout/item_message.xml` içerisindeki `android:autoLink="email|web"` özelliği kaldırıldı ve `android:linksClickable="false"` eklendi.
  - `ThreadAdapter.kt` içerisinde `movementMethod = null`, `linksClickable = false` ve `setLinkTextColor(textColor)` ayarlandı.
  - Bağlantılar artık normal metinle birebir aynı renkte düz metin olarak gösterilir ve üzerine tıklandığında hiçbir dış bağlantı açılmaz. Kullanıcı gerekirse mesaja basılı tutup kopyalayabilir veya *"Select text"* penceresinden ilgili kısmı seçip kopyalayabilir.

---

## 4. Manifest, İzin ve Bileşen Sıkılaştırmaları (Standart)

Ayarlar ekranına ek bir seçenek eklenmeden, tüm derlemelerde standart olarak geçerli olacak şekilde `AndroidManifest.xml` ve bileşen sınırları sıkılaştırıldı:

1. **Ağ ve Telemetri İzinlerinin APK'dan Kazınması (`tools:node="remove"`):**
   - Alt bağımlılıklardan gelebilecek ağ ve genel ayar izinleri birleştirilmiş manifestten tamamen silindi:
     - `android.permission.INTERNET`
     - `android.permission.ACCESS_NETWORK_STATE`
     - `android.permission.CHANGE_NETWORK_STATE`
     - `android.permission.ACCESS_WIFI_STATE`
     - `org.fossify.android.permission.WRITE_GLOBAL_SETTINGS`
     - `com.samsung.android.providers.context.permission.WRITE_USE_APP_FEATURE_SURVEY`
2. **Bulut ve ADB Yedeklemesinin Kapatılması:**
   - `<application android:allowBackup="false" tools:replace="android:allowBackup">` ayarlanarak SMS veritabanının ve uygulama ayarlarının buluta veya ADB yedeğine çıkarılması engellendi.
3. **Dışa Açık (`exported="true"`) Bileşenlerin Kapatılması:**
   - **Yerel Zafiyet Düzeltmesi (`MmsReceiver`):** Orijinal kodda izin korumasız olarak `android:exported="true"` bırakılan `.receivers.MmsReceiver`, `android:exported="false"` yapıldı.
   - **`FossifyBroadcastReceiver` Kaldırılması:** `org.fossify:commons` kütüphanesinin dışarıya açık (`exported="true"`) olarak eklediği `org.fossify.commons.receivers.FossifyBroadcastReceiver` alıcısı `tools:node="remove"` ile tamamen kaldırıldı.
   - **`EmojiCompatInitializer` Kaldırılması:** `AppCompat` kütüphanesinin uygulama açılışında GMS üzerinden indirilebilir emoji fontu yüklemek için eklediği `androidx.emoji2.text.EmojiCompatInitializer` başlangıç bileşeni `tools:node="remove"` ile kaldırıldı.
   - **İç Aktivitelerin Kapatılması:** `MainActivity`, `RecycleBinConversationsActivity` ve `ArchivedConversationsActivity` üzerindeki gereksiz `android:exported="true"` tanımları `android:exported="false"` yapıldı.
4. **Harici Dosya Paylaşım (`ACTION_SEND` / `*/*`) Kapısının Kapatılması:**
   - `NewConversationActivity` içerisinden `android.intent.action.SEND` ve `android.intent.action.SEND_MULTIPLE` (`mimeType="*/*"`) intent filtreleri kaldırıldı.
   - `NewConversationActivity.kt` içerisindeki `Intent.EXTRA_STREAM` harici dosya eki işleme kodu silindi (varsayılan SMS uygulaması rolü için zorunlu olan `SENDTO`/`VIEW` `sms:` şeması korundu).

---

## 5. Değiştirilen ve Eklenen Dosyaların Listesi

| Dosya Yolu | Değişiklik Türü | Açıklama |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | Güncellendi | Ağ izinleri kaldırıldı, `allowBackup="false"` yapıldı, `WapPushReceiver` bağlandı, `MmsReceiver` ve iç aktiviteler `exported="false"` yapıldı, `FossifyBroadcastReceiver`, `EmojiCompatInitializer` ve `ACTION_SEND` (`*/*`) kaldırıldı. |
| `app/src/main/kotlin/org/fossify/messages/receivers/WapPushReceiver.kt` | **Yeni Dosya** | `WAP_PUSH_DELIVER` yayınlarını karşılar; `blockMms` açıkken PDU parse etmeden ve MMSC'ye bağlanmadan `(MMS engellendi)` kaydı oluşturur. |
| `app/src/main/kotlin/org/fossify/messages/receivers/MmsReceiver.kt` | Güncellendi | `blockMms` açıkken MMS işleme ve bildirim oluşturmayı durdurur. |
| `app/src/main/kotlin/org/fossify/messages/extensions/String.kt` | Güncellendi | `sanitizeForDisplay()` ve `isAllowedRobotoKeyboardCodePoint()` ile yalnızca `Roboto` standart klavye karakterlerine izin veren beyaz liste eklendi. |
| `app/src/main/kotlin/org/fossify/messages/extensions/Context.kt` | Güncellendi | `getMMS()` içinde `blockMms` kontrolü ve `sanitizeTextIfNeeded()` uzantı fonksiyonu eklendi. |
| `app/src/main/kotlin/org/fossify/messages/helpers/Constants.kt` | Güncellendi | `BLOCK_MMS` ve `SANITIZE_MESSAGE_TEXT` anahtar sabitleri eklendi. |
| `app/src/main/kotlin/org/fossify/messages/helpers/Config.kt` | Güncellendi | `blockMms` (varsayılan: `false`) ve `sanitizeMessageText` (varsayılan: `true`) ayar özellikleri eklendi. |
| `app/src/main/kotlin/org/fossify/messages/adapters/ThreadAdapter.kt` | Güncellendi | Mesaj balonlarında tıklanabilir linkler ve mavi link rengi kaldırıldı, `sanitizeTextIfNeeded()` ve `Typeface.DEFAULT` (`Roboto`) uygulandı, `blockMms` açıkken eklerin çizilmesi engellendi. |
| `app/src/main/kotlin/org/fossify/messages/adapters/BaseConversationsAdapter.kt` | Güncellendi | Konuşma başlığı ve özetlerinde `sanitizeTextIfNeeded()` ve `Typeface.DEFAULT` (`Roboto`) uygulandı. |
| `app/src/main/kotlin/org/fossify/messages/adapters/SearchResultsAdapter.kt` | Güncellendi | Arama sonuçlarında `sanitizeTextIfNeeded()` uygulandı. |
| `app/src/main/kotlin/org/fossify/messages/helpers/NotificationHelper.kt` | Güncellendi | Bildirim başlığı ve mesaj içeriğinde `sanitizeTextIfNeeded()` uygulandı. |
| `app/src/main/kotlin/org/fossify/messages/dialogs/SelectTextDialog.kt` | Güncellendi | Metin seçme diyaloğunda `sanitizeTextIfNeeded()` uygulandı. |
| `app/src/main/kotlin/org/fossify/messages/activities/ThreadActivity.kt` | Güncellendi | `blockMms` açıkken ek (attachment) ekleme butonu ve fonksiyonu devre dışı bırakıldı. |
| `app/src/main/kotlin/org/fossify/messages/activities/NewConversationActivity.kt` | Güncellendi | Harici uygulamalardan `EXTRA_STREAM` ile dosya eki enjeksiyonu kaldırıldı. |
| `app/src/main/kotlin/org/fossify/messages/messaging/Messaging.kt` | Güncellendi | `blockMms` açıkken giden mesajların MMS olarak gönderilmesi engellendi. |
| `app/src/main/kotlin/org/fossify/messages/activities/SettingsActivity.kt` | Güncellendi | `setupBlockMms()` ve `setupSanitizeMessageText()` ayar bağlamaları eklendi. |
| `app/src/main/res/layout/item_message.xml` | Güncellendi | `android:autoLink="email\|web"` kaldırıldı, `android:linksClickable="false"` eklendi. |
| `app/src/main/res/layout/activity_settings.xml` | Güncellendi | Security bölümüne `settings_block_mms` ve `settings_sanitize_message_text` anahtarları eklendi. |
| `app/src/main/res/values/donottranslate.xml` | Güncellendi | İngilizce ayar metinleri ve `(MMS engellendi)` / `(Unsupported characters removed)` yer tutucuları eklendi. |
