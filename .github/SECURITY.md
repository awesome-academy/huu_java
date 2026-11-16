# Chính sách bảo mật

Repo này **public**. Không báo lỗ hổng bảo mật qua Issue, Pull Request hay comment công khai.

## Báo cáo lỗ hổng

Báo kín qua **GitHub Security Advisory**: [Report a vulnerability](https://github.com/awesome-academy/huu_java/security/advisories/new).

Nếu link chưa dùng được (repo chưa bật *Private vulnerability reporting*), nhắn riêng cho trainer / người phụ trách dự án.

Nội dung nên có:

- Thành phần và URL / endpoint bị ảnh hưởng
- Các bước tái tạo, kết quả mong đợi và thực tế
- Mức độ ảnh hưởng (lộ dữ liệu, chiếm quyền, từ chối dịch vụ...)

Không đính kèm secret thật (password, JWT, client secret, webhook URL, cookie) — chỉ ghi là có lộ và ở đâu.

## Những gì được tính là lỗ hổng

- Lộ secret / token / dữ liệu cá nhân (trong code, log, response, lịch sử git)
- Bypass đăng nhập hoặc phân quyền, truy cập dữ liệu của người khác (IDOR)
- Injection (SQL, log, formula Excel, XXE), XSS, CSRF
- Upload file nguy hiểm, path traversal

## Xử lý secret bị lộ

Secret đã lên commit (kể cả đã xoá ở commit sau) được coi là bị lộ: **thu hồi / tạo lại** ở nhà cung cấp (Google Console, Mailtrap, Slack...), sau đó cập nhật `.env` local. Quy chuẩn bảo mật của dự án: CLAUDE.md, mục "OWASP Secure Coding".
