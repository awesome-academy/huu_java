#!/bin/sh
# Entrypoint của service `maven` trong docker-compose.yaml (gọi qua `sh`, không cần quyền thực thi).
# Container khởi động bằng root, rồi chạy Maven bằng đúng chủ sở hữu thư mục project được mount vào:
#   - Docker Desktop (Windows/Mac): bind mount hiện là root:root và Docker Desktop tự ánh xạ quyền sở hữu về user
#     máy host → chạy bằng root. Không thể chạy bằng UID khác: user không phải root không đặt được thời gian sửa đổi
#     (utime) cho file có sẵn trong target/, maven-resources-plugin báo "Operation not permitted".
#   - Linux/WSL2: thư mục thuộc UID thật của user máy host → hạ quyền về UID/GID đó, nên target/ và mọi file
#     test ghi ra thuộc user máy host ngay từ đầu (không cần chown sau khi chạy, kể cả khi container bị dừng giữa chừng).
# HOME, MAVEN_USER_HOME, maven.repo.local trỏ vào volume maven-repo ở /m2 (đặt trong docker-compose.yaml).
set -eu

owner="$(stat -c %u:%g .)"

if [ "$owner" = "0:0" ]; then
  exec sh ./mvnw -B "$@"
fi

# Volume mới tạo (hoặc volume cũ từ lúc còn chạy bằng root) thuộc root: chuyển cho user máy host, chỉ làm khi cần.
if [ "$(stat -c %u:%g /m2)" != "$owner" ]; then
  chown -R "$owner" /m2
fi

# Service maven-docker mount Docker socket (Testcontainers): socket thuộc root:docker trên Linux/WSL2, user máy host chỉ
# dùng được nhờ group docker — giữ đúng GID của socket làm group phụ, ngoài ra không giữ group nào của root.
groups="--clear-groups"
if [ -S /var/run/docker.sock ]; then
  groups="--groups=$(stat -c %g /var/run/docker.sock)"
fi

# exec: Maven (java) nhận trực tiếp tín hiệu dừng của Docker.
exec setpriv --reuid="${owner%%:*}" --regid="${owner##*:}" "$groups" sh ./mvnw -B "$@"
