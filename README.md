# TranslatorEV cho Android — Thông dịch Anh ⇄ Việt

Ứng dụng điện thoại dịch hai chiều Anh ⇄ Việt: gõ chữ hoặc **nói vào micro**,
có **chế độ hội thoại 2 chiều**, **đọc to bản dịch** và **lịch sử**.

Miễn phí, không cần tài khoản, không quảng cáo. Cần Internet để dịch.

> **Vì sao tôi không gửi thẳng file .apk?**
> Máy chủ nơi bộ mã này được tạo ra bị chặn toàn bộ kho tải (Google, Maven,
> Gradle) nên không cài được Android SDK để biên dịch. Bên dưới là hai cách
> lấy `.apk` mà bạn **không phải cài Android Studio**.

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

> APK được ký bằng khoá debug nên cài trực tiếp được ngay. Chỉ khi muốn đưa
> lên CH Play mới cần tạo khoá ký riêng.

---

## Cách 3 — Build bằng Android Studio

Tải Android Studio, chọn **Open**, trỏ vào thư mục này, chờ Gradle đồng bộ,
rồi **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
File nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

---

## Ứng dụng làm được gì

**Tab Dịch** — gõ chữ hoặc bấm 🎤 nói. Tự nhận diện câu đang là tiếng Anh hay
tiếng Việt rồi dịch sang chiều còn lại. Có nút đọc to, sao chép, đảo chiều.

**Tab Hội thoại** — hai nút lớn *Speak English* và *Nói tiếng Việt*. Người nào
nói thì bấm nút của ngôn ngữ đó; máy nghe → dịch → hiện bong bóng → đọc lên.
Hợp lúc nói chuyện trực tiếp với người nước ngoài.

**Tab Lịch sử** — lưu lại mọi câu đã dịch, tìm kiếm, dùng lại, đọc to, xoá,
xuất ra TXT/CSV vào thư mục *Tải về*.

---

## Nó chạy bằng gì

| Việc | Trong app Android | Trong trình duyệt / PWA |
|---|---|---|
| Dịch | MyMemory → Google, gọi qua Java | cùng vậy, gọi bằng `fetch` |
| Nghe giọng nói | `SpeechRecognizer` của Android (vi-VN, en-US) | Web Speech API |
| Đọc to | `TextToSpeech` của Android | `speechSynthesis` |
| Nhận diện ngôn ngữ | ngay trên máy: dấu tiếng Việt, rồi tới bộ từ vựng cho trường hợp gõ không dấu |

Hai nguồn dịch dự phòng cho nhau: nguồn nào lỗi thì tự chuyển sang nguồn kia.
Đổi thứ tự ưu tiên ở ô chọn góc trên bên phải.

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
Kiểm tra mạng. Nếu vẫn lỗi, đổi nguồn dịch ở góc trên bên phải — hai dịch vụ
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
