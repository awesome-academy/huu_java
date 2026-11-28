# 🧼 SOAP — Web Service Cho Hệ Thống Đối Tác

SOAP service (Spring-WS, **contract-first**) để hệ thống bên ngoài tra cứu **sản phẩm đang bán** và **trạng thái đơn hàng**.

## Tổng Quan

| | |
|---|---|
| **Endpoint** | `POST /ws` (SOAP 1.1, `Content-Type: text/xml; charset=utf-8`) |
| **WSDL** | `GET /ws/foodsndrinks.wsdl` — sinh tự động từ XSD, không cần đăng nhập |
| **Hợp đồng (XSD)** | [`src/main/resources/xsd/foodsndrinks.xsd`](../../src/main/resources/xsd/foodsndrinks.xsd), namespace `urn:foodsndrinks:store` |
| **Xác thực** | HTTP Basic bằng tài khoản của app (username hoặc email + mật khẩu) |
| **Cấu hình** | `config/WebServiceConfig` (interceptor, fault, WSDL), filter chain `/ws/**` trong `config/SecurityConfig` |
| **Endpoint class** | `controller/soap/StoreEndpoint` — chỉ chuyển đổi JAXB ↔ `ProductService` / `OrderService` |

Class JAXB (`com.huuhv.foodsndrinks.soap.store.*`) được **sinh lúc build** từ XSD bằng `jaxb2-maven-plugin` vào `target/generated-sources/jaxb` — không nằm trong git, không sửa tay. IDE báo thiếu class thì chạy `./mvnw generate-sources` (hoặc `docker compose run --rm --no-deps maven generate-sources`).

> `/ws` (SOAP) khác `/ws-stomp` (WebSocket của trang web) — hai đường dẫn, hai filter chain riêng.

## Xác Thực & Bảo Mật

- **Mọi operation cần HTTP Basic.** Thiếu hoặc sai tài khoản → `401` + header `WWW-Authenticate: Basic realm="foodsndrinks-soap"`, **không có body** (không redirect `/login`, không trang HTML). Chỉ file WSDL là public.
- **Stateless:** không session, không cookie — vì vậy CSRF được tắt *chỉ* cho `/ws/**`. Cookie đăng nhập của trình duyệt **không** phải danh tính với SOAP.
- Tài khoản được kiểm tra lại ở **mỗi request**: bị khoá → `401` ngay. Giới hạn đăng nhập sai áp dụng như form/API (sai 5 lần / 15 phút → tạm khoá, xem [docs/api](../api/README.md#xác-thực)). Audit ghi `LOGIN_SUCCESS` / `LOGIN_FAILURE` với `auth.method=SOAP`.
- Tài khoản chỉ đăng nhập bằng Google/Facebook/X (không có mật khẩu) không dùng được SOAP.
- **Phân quyền theo dữ liệu:** `GetOrderStatus` chỉ trả đơn của chính người gọi; `ROLE_ADMIN` xem được mọi đơn. Đơn của người khác được trả lời **y hệt** đơn không tồn tại (chống dò mã đơn).
- **Request được validate theo XSD** trước khi tới code; response cũng được validate trước khi gửi.
- **Giới hạn kích thước:** body tối đa `app.soap.max-request-size` (64 KB) → `413`.
- **XML không đọc được, không phải SOAP, hoặc có `<!DOCTYPE>`** → `400` không body. DTD bị từ chối nên không có XXE.
- Prod: chỉ gọi qua HTTPS (HTTP Basic gửi mật khẩu ở dạng base64, không mã hoá).

## Operations

### `GetProducts` — sản phẩm đang bán

| Tham số | Kiểu | Ghi chú |
|---|---|---|
| `categoryId` | long ≥ 1, tuỳ chọn | Bỏ trống = mọi danh mục. Danh mục không tồn tại → danh sách rỗng |
| `page` | int 0–100000, tuỳ chọn | Trang, bắt đầu từ 0. Mặc định `0` |
| `size` | int **1–100**, tuỳ chọn | Mặc định `10`. Ngoài khoảng → Fault |

Response: `product*` (`id`, `name`, `slug`, `category` — không có nếu sản phẩm chưa thuộc danh mục, `type` = `FOOD`/`DRINK`, `price` VND), `page`, `size`, `totalElements`, `totalPages`. Sản phẩm mới nhất trước; sản phẩm đã ẩn không được trả.

```bash
curl -s -u alice:'mat-khau-cua-alice' http://localhost:8080/ws \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                         xmlns:s="urn:foodsndrinks:store">
        <soapenv:Body>
          <s:GetProductsRequest>
            <s:categoryId>3</s:categoryId>
            <s:page>0</s:page>
            <s:size>2</s:size>
          </s:GetProductsRequest>
        </soapenv:Body>
      </soapenv:Envelope>'
```

```xml
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
  <SOAP-ENV:Header/>
  <SOAP-ENV:Body>
    <ns2:GetProductsResponse xmlns:ns2="urn:foodsndrinks:store">
      <ns2:product>
        <ns2:id>12</ns2:id>
        <ns2:name>Trà sữa trân châu</ns2:name>
        <ns2:slug>tra-sua-tran-chau</ns2:slug>
        <ns2:category>Đồ uống</ns2:category>
        <ns2:type>DRINK</ns2:type>
        <ns2:price>35000.00</ns2:price>
      </ns2:product>
      <ns2:page>0</ns2:page>
      <ns2:size>2</ns2:size>
      <ns2:totalElements>1</ns2:totalElements>
      <ns2:totalPages>1</ns2:totalPages>
    </ns2:GetProductsResponse>
  </SOAP-ENV:Body>
</SOAP-ENV:Envelope>
```

### `GetOrderStatus` — trạng thái một đơn

| Tham số | Kiểu | Ghi chú |
|---|---|---|
| `orderId` | long ≥ 1, bắt buộc | Mã đơn |

Response: `orderId`, `status` (`PENDING` / `PROCESSING` / `COMPLETED` / `CANCELLED`), `statusLabel` (nhãn tiếng Việt, vd. `Đang xử lý`). Giỏ hàng chưa đặt không phải đơn.

```bash
curl -s -u alice:'mat-khau-cua-alice' http://localhost:8080/ws \
  -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>
          <GetOrderStatusRequest xmlns="urn:foodsndrinks:store"><orderId>42</orderId></GetOrderStatusRequest>
        </soapenv:Body>
      </soapenv:Envelope>'
```

```xml
<ns2:GetOrderStatusResponse xmlns:ns2="urn:foodsndrinks:store">
  <ns2:orderId>42</ns2:orderId>
  <ns2:status>PROCESSING</ns2:status>
  <ns2:statusLabel>Đang xử lý</ns2:statusLabel>
</ns2:GetOrderStatusResponse>
```

## Lỗi

Lỗi của một message SOAP hợp lệ là **SOAP Fault** (HTTP `500` theo SOAP 1.1) với thông điệp **cố định** — không bao giờ chứa thông điệp của exception, tên bảng, stack trace hay giá trị client gửi:

| Tình huống | `faultcode` | `faultstring` |
|---|---|---|
| Request sai XSD (thiếu field, sai kiểu, `size` > 100, phần tử lạ) | `Client` | `Validation error` |
| Đơn không tồn tại **hoặc không phải của người gọi** | `Client` | `Resource not found` |
| Tham số không hợp lệ về nghiệp vụ | `Client` | `Invalid request` |
| Lỗi hệ thống (chi tiết chỉ nằm trong log của server, kèm `requestId`) | `Server` | `Internal error` |

```xml
<SOAP-ENV:Fault>
  <faultcode>SOAP-ENV:Client</faultcode>
  <faultstring xml:lang="en">Resource not found</faultstring>
</SOAP-ENV:Fault>
```

Lỗi ở tầng HTTP (không có SOAP envelope trong response):

| Mã | Khi nào |
|---|---|
| `401` | Thiếu/sai HTTP Basic, tài khoản bị khoá hoặc đang bị tạm khoá vì đăng nhập sai nhiều lần |
| `400` | Body không phải XML/SOAP hợp lệ, hoặc có `<!DOCTYPE>` |
| `405` | Không phải `POST` (trừ `GET /ws/foodsndrinks.wsdl`) |
| `413` | Body lớn hơn `app.soap.max-request-size` |

Mọi response có header `X-Request-Id` — gửi kèm khi báo lỗi để tra log.

## Gọi Thử

- **`curl`:** các ví dụ ở trên (thay tài khoản; app chạy bằng `docker compose up -d --build` hoặc `./mvnw spring-boot:run`).
- **SoapUI / Postman:** tạo project từ WSDL `http://localhost:8080/ws/foodsndrinks.wsdl`, đặt *Authorization → Basic* với tài khoản của app. Địa chỉ service trong WSDL tự theo host bạn dùng để tải WSDL (đúng cả sau reverse proxy).
- Kiểm tra nhanh xác thực: `curl -i -X POST http://localhost:8080/ws` → `401` + `WWW-Authenticate`.

## Quy Ước Khi Thêm Operation

1. **Sửa XSD trước** (`<Tên>Request` / `<Tên>Response`): ràng buộc dữ liệu vào bằng kiểu XSD (khoảng giá trị, enum, độ dài). Chỉ **thêm** phần tử tuỳ chọn vào message đã có — client đang chạy validate theo WSDL cũ.
2. Build lại để sinh class JAXB, thêm method `@PayloadRoot` vào endpoint ở `controller/soap/` — endpoint chỉ gọi service và map sang class JAXB, không trả entity.
3. **Phân quyền nằm ở service**, lọc theo người gọi (`Authentication#getName()`): mọi operation dùng chung URL `/ws` nên `SecurityConfig` không phân quyền theo operation được. Không mở `permitAll` cho đường dẫn con của `/ws`.
4. Exception nghiệp vụ mới cần fault riêng → thêm mapping trong `WebServiceConfig.FaultResolver` với thông điệp cố định.
5. Test: `@WebServiceServerTest` + `MockWebServiceClient` (thành công, sai XSD, sai quyền, lỗi bất ngờ không lộ thông điệp) như `StoreEndpointTest`; bảo mật HTTP như `SoapHttpIntegrationTest`.
6. Cập nhật file này.
