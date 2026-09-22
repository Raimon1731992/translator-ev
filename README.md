# TranslatorEV cho Android — Thông dịch Anh ⇄ Việt

Ứng dụng điện thoại dịch hai chiều Anh ⇄ Việt: gõ chữ hoặc **nói vào micro**,
có **chế độ hội thoại 2 chiều**, **đọc to bản dịch** và **lịch sử**.

---

## Có gì mới ở bản 1.1

**Micro ở tab Dịch giờ có hai nút riêng** — *Nói English* và *Nói Tiếng Việt*.
Bản 1.0 để chế độ AUTO thì luôn nghe bằng `en-US`, nên nói tiếng Việt vào là máy
cố phiên âm thành tiếng Anh và ra kết quả vô nghĩa. Bộ nhận dạng của Android bắt
buộc phải biết trước ngôn ngữ, không tự đoán được, nên cách đúng là hỏi thẳng.

**Hết lỗi micro mã 11 ở tab Hội thoại.** Mã 11 là `ERROR_SERVER_DISCONNECTED`:
bản 1.0 huỷ rồi tạo lại `SpeechRecognizer` sau mỗi lượt nghe, việc nối lại dịch
vụ ngay lập tức gây tranh chấp. Bản này giữ một instance dùng suốt, chỉ `cancel()`
giữa các lượt, tự huỷ và thử lại một lần khi gặp lỗi, và nếu vẫn hỏng thì chuyển
sang **hộp thoại nhận dạng sẵn có của Google** — thứ chạy ổn trên hầu hết máy.

**Tự lo gói ngôn ngữ tiếng Việt.** Trên Android 13 trở lên, app dùng
`checkRecognitionSupport` để xem máy đã có gói nhận dạng chưa, thiếu thì gọi
`triggerModelDownload` tải về. Kiểm tra thủ công được trong ⚙ Cài đặt.

**Dịch chính xác hơn.** Google Translate giờ là nguồn chính (bản 1.0 để MyMemory
đứng đầu — đó là nguồn yếu nhất trong nhóm). Thứ tự dự phòng: Google → Lingva →
MyMemory. Thêm tuỳ chọn **Claude** và **DeepL** cho ai cần chất lượng cao nhất.

**Cài đè được.** Dự án nay có khoá ký cố định, nên bản build mới cài chồng lên
bản cũ mà không phải gỡ app, lịch sử dịch giữ nguyên.

---

## Về việc dùng Claude làm nguồn dịch

Claude dịch tự nhiên và bám ngữ cảnh tốt hơn hẳn các API miễn phí — nó hiểu sắc
thái, giữ được giọng điệu, xử lý được câu dài nhiều mệnh đề. Trong app đã có sẵn
đường nối tới `api.anthropic.com` kèm câu lệnh hệ thống viết riêng cho việc dịch
Anh–Việt.

Nhưng có một điều tôi phải nói thẳng: **không thể nhúng sẵn khoá API vào file
.apk**. APK giải nén được, ai cầm file cũng đọc ra khoá và tiêu tiền trên tài
khoản của bạn. Vì vậy app để bạn **tự dán khoá của mình** trong ⚙ Cài đặt, khoá
nằm trong bộ nhớ riêng của ứng dụng trên máy bạn.

Lấy khoá ở [console.anthropic.com](https://console.anthropic.com) (trả theo lượt
dùng, dịch một câu tốn chưa tới một xu) hoặc DeepL ở
[deepl.com/pro-api](https://www.deepl.com/pro-api) (gói miễn phí 500.000 ký tự
mỗi tháng). Chọn nguồn trong ⚙ Cài đặt → *Nguồn dịch ưu tiên*.

Không nhập khoá thì app vẫn chạy đầy đủ bằng Google — chỉ là chất lượng dịch
kém hơn Claude một bậc.

---

## Cập nhật lên bản mới

Vào repo trên GitHub → **Add file** → **Upload files** → mở thư mục
`TranslatorEV-Android`, bấm <kbd>Ctrl</kbd>+<kbd>A</kbd>, kéo tất cả vào →
**Commit changes**. File trùng đường dẫn sẽ được ghi đè, Actions tự build lại.

Tải APK mới ở tab Actions → Artifacts, cài đè lên bản cũ được luôn.

---

## Cách 1 — Dùng ngay trong 30 giây (không cần .apk)

1. Đưa thư mục `web/` lên bất kỳ chỗ nào chạy **https** (GitHub Pages,
   Netlify, Cloudflare Pages… đều có gói miễn phí).
2. Mở địa chỉ đó bằng **Chrome trên điện thoại**.
3. Menu ⋮ → **Thêm vào Màn hình chính**.

Bạn sẽ có icon ngoài màn hình chính, mở ra chạy toàn màn hình như app thật,
và **micro hoạt động** vì trang chạy qua https.

Muốn thử nhanh trên máy tính: mở thẳng `web/index.html` bằng Chrome — dịch và
đọc to chạy được, chỉ micro bị chặn (do mở từ ổ đĩa).

---

## Cách 2 — Lấy file .apk thật, không cài gì trên máy

GitHub build hộ bạn miễn phí. Khoảng 10 phút, chỉ cần một tài khoản GitHub.

1. Vào <https://github.com/new>, tạo repo mới (để **Private** cũng được).
2. Ở trang repo vừa tạo, bấm **uploading an existing file**, rồi **kéo thả
   toàn bộ nội dung bên trong thư mục này** (kể cả thư mục ẩn `.github`).
   - Nếu trình duyệt không cho kéo thư mục ẩn: cài **GitHub Desktop**, hoặc
     chạy trong Command Prompt tại thư mục này:
     ```bat
     git init
     git add .
     git commit -m "TranslatorEV"
     git branch -M main
     git remote add origin https://github.com/<tên-bạn>/<tên-repo>.git
     git push -u origin main
     ```
3. Mở tab **Actions** của repo. Bạn sẽ thấy job **Build APK** đang chạy
   (nếu chưa chạy: chọn *Build APK* ở cột trái → **Run workflow**).
4. Chờ ~5 phút cho đến khi hiện dấu ✅.
5. Bấm vào lần chạy đó, kéo xuống mục **Artifacts** → tải
   **TranslatorEV-apk** (là file zip chứa `TranslatorEV.apk`).
6. Chép `TranslatorEV.apk` sang điện thoại và mở nó. Android sẽ hỏi
   *"Cho phép cài từ nguồn này?"* → bật cho phép → **Cài đặt**.

> APK được ký bằng khoá cố định kèm trong dự án (`app/keystore/`), nên cài
> trực tiếp được ngay và các bản sau cài đè lên được. Khoá này chỉ dùng cho bản
> tự dùng; muốn đưa lên CH Play thì phải tạo khoá ký riêng và giữ bí mật.

---

## Cách 3 — Build bằng Android Studio

Tải Android Studio, chọn **Open**, trỏ vào thư mục này, chờ Gradle đồng bộ,
rồi **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
File nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

---

## Ứng dụng làm được gì

**Tab Dịch** — gõ chữ, hoặc bấm đúng nút micro theo ngôn ngữ bạn sắp nói
(*Nói English* / *Nói Tiếng Việt*). Với văn bản gõ tay, app tự nhận diện ngôn ngữ.
Có nút đọc to, sao chép, đảo chiều.

**Tab Hội thoại** — hai nút lớn *Speak English* và *Nói tiếng Việt*. Người nào
nói thì bấm nút của ngôn ngữ đó; máy nghe → dịch → hiện bong bóng → đọc lên.
Hợp lúc nói chuyện trực tiếp với người nước ngoài.

**Tab Lịch sử** — lưu lại mọi câu đã dịch, tìm kiếm, dùng lại, đọc to, xoá,
xuất ra TXT/CSV vào thư mục *Tải về*.

---

## Nó chạy bằng gì

| Việc | Trong app Android | Trong trình duyệt / PWA |
|---|---|---|
| Dịch | Google → Lingva → MyMemory, gọi qua Java (thêm Claude/DeepL nếu có khoá) | cùng vậy, gọi bằng `fetch` |
| Nghe giọng nói | `SpeechRecognizer` của Android (vi-VN, en-US) | Web Speech API |
| Đọc to | `TextToSpeech` của Android | `speechSynthesis` |
| Nhận diện ngôn ngữ | ngay trên máy: dấu tiếng Việt, rồi tới bộ từ vựng cho trường hợp gõ không dấu |

Các nguồn dịch dự phòng cho nhau: nguồn nào lỗi thì tự chuyển sang nguồn kế tiếp.
Chọn nguồn ưu tiên trong ⚙ Cài đặt.

App gọi mạng bằng Java thay vì JavaScript vì WebView nạp trang từ `file://`
sẽ bị trình duyệt chặn CORS.

Lịch sử lưu trong máy bạn (`localStorage` của WebView). Không có máy chủ nào
của chúng tôi — chỉ nội dung câu cần dịch được gửi tới dịch vụ dịch.

---

## Quyền ứng dụng xin

| Quyền | Để làm gì |
|---|---|
| `INTERNET` | gọi dịch vụ dịch |
| `RECORD_AUDIO` | nghe giọng nói qua micro (chỉ hỏi khi bạn bấm 🎤) |
| `WRITE_EXTERNAL_STORAGE` | chỉ trên Android 9 trở xuống, để lưu file TXT/CSV |

---

## Cấu trúc

```
app/src/main/
  java/com/skymavis/translatorev/MainActivity.java   WebView + cầu nối Java↔JS
  assets/index.html                                  toàn bộ giao diện & logic
  keystore/translatorev.jks(.base64)                 khoá ký cố định
  res/mipmap-*/                                      icon 5 độ phân giải
  AndroidManifest.xml
web/                        bản PWA (cùng index.html + manifest + service worker)
.github/workflows/build-apk.yml   GitHub tự build .apk
build.gradle, app/build.gradle, settings.gradle
```

Ứng dụng **không dùng thư viện ngoài nào** — chỉ API có sẵn của Android, nên
build nhanh và APK rất nhẹ (khoảng 1–2 MB).

---

## Xử lý sự cố

**Bấm 🎤 báo "Máy chưa có dịch vụ nhận dạng giọng nói"**
Cài hoặc bật lại ứng dụng **Google** trong CH Play — Android mượn bộ nhận dạng
của nó. Một số máy Xiaomi/Huawei tắt sẵn ứng dụng này.

**Đọc tiếng Việt không ra tiếng**
Vào *Cài đặt → Hệ thống → Ngôn ngữ → Đầu ra chuyển văn bản thành lời nói*,
chọn Google Speech Services và tải gói giọng **Tiếng Việt**.

**"Không dịch được"**
Kiểm tra mạng. Nếu vẫn lỗi, đổi nguồn dịch trong ⚙ Cài đặt — các dịch vụ
miễn phí này đều có hạn mức ẩn và đôi khi chặn tạm thời.

**Android chặn cài đặt**
Đây là app tự build, chưa qua CH Play. Khi mở file `.apk`, chọn
*Cài đặt bằng mọi cách* / bật *Cho phép từ nguồn này* cho Trình quản lý tệp.

**GitHub Actions báo đỏ**
Mở lần chạy bị lỗi, bấm vào bước màu đỏ, copy đoạn log gửi lại cho tôi.
Nguyên nhân hay gặp nhất là thiếu thư mục `.github` khi tải lên.

---

## Giới hạn

- Cần Internet: cả dịch lẫn nhận dạng giọng nói đều gọi dịch vụ trực tuyến.
- Chỉ cặp Anh ⇄ Việt. Muốn thêm ngôn ngữ: sửa `FULL`, `detect()` và các mã
  `vi-VN` / `en-US` trong `index.html`.
- Nhận dạng giọng nói cần chọn đúng ngôn ngữ trước khi nói (ở tab Hội thoại là
  chọn bằng nút; ở tab Dịch, chế độ AUTO mặc định nghe tiếng Anh).
