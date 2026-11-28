-- Đơn đã đặt (khác CART) từ trước khi có cột ordered_at / được admin chuyển thẳng từ CART (đã chặn) có thể còn
-- ordered_at NULL. Dashboard, danh sách đơn và lịch sử đơn giờ lọc + sắp xếp theo ordered_at (created_at là lúc tạo
-- giỏ hàng), nên các đơn đó lấy created_at làm thời điểm đặt — giá trị gần đúng nhất còn lại.
UPDATE orders
SET ordered_at = created_at
WHERE status <> 'CART'
  AND ordered_at IS NULL;
