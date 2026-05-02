# Bookstore Payment Service

## 1. TỔNG QUAN (OVERVIEW)
- **Tên Service:** `bookstore-payment-service`
- **Mục đích:** Xử lý các giao dịch thanh toán cho hệ thống qua bên thứ 3 (VNPay, ZaloPay, MoMo). Sau khi thanh toán thành công, gửi sự kiện qua Kafka để các service khác xử lý tiếp (ví dụ `order-service` cập nhật trạng thái đơn hàng).
- **Port mặc định:** Tuỳ cấu hình trong `application.yml` (hoặc `docker-compose.yml`), mặc định có thể là `8087` hoặc `8080`.

## 2. CONFIG, START VÀ ENVIRONMENT
**Cách chạy nhanh qua Docker Compose:**
```bash
cd bookstore-payment-service
docker compose -f docker-compose.dev.yml up -d
```

**Cấu hình application.yaml cần thiết:**
- **Database:** Connect tới `mysql` (port `3310` dev) / (Database `payment_db`).
- **Kafka:** Connect tới cluster Kafka (port định tuyến `9092`). Topic sử dụng: `payment.completed`.
- **Payment Keys:** Bắt buộc cấu hình đầy đủ `payment.vnpay.*`, `payment.momo.*`, `payment.zaloPay.*` gồm các key mã hóa, access key, endpoint gọi API, redirect/callback URL.

## 3. MODELS & DTOs
**Entity `Payment`:** Chứa thông tin lịch sử tạo phiên giao dịch payment độc lập không còn mapping trưc tiếp sang `Order`.
- `paymentID` (Long, PK)
- `orderId` (UUID) - ID để map ngược với bảng `Order` ở `order-service`
- `amount` (Long)
- `paymentMethod` (String: VNPAY, ZALOPAY, MOMO)
- `status` (Enum: PENDING, COMPLETED, FAILED, REFUNDED)
- `transactionId` (String)
- `redirectUrl` (String)

**DTO `PaymentRequest`:**
```json
{
  "orderId": "UUID",
  "amount": 150000,
  "orderInfo": "Thanh toan don hang abc",
  "bankCode": "NCB",
  "redirectUrl": "https://fontend.domain/redirect"
}
```

## 4. DANH SÁCH API CUNG CẤP CỦA PAYMENT SERVICE
### 4.1. VNPay API
- **API 4.1.1: Tạo URL Thanh Toán VNPay**
  - Mục đích: Trả về một URL đến cổng thanh toán VNPay
  - Method: `POST`
  - Endpoint: `/api/payment/vnpay/create`
  - Request Body: `PaymentRequest`
  - Response (200 OK): `CreatePaymentResponse` chứa `paymentUrl`.

- **API 4.1.2: VNPay Return Callback**
  - Mục đích: VNPay tự động gọi/redirect về sau khi khách hàng dùng thử trên web VNPay thành công/thất bại.
  - Method: `GET`
  - Endpoint: `/api/payment/vnpay/callback`
  - Tham số: `vnp_*` từ VNPay. Thực hiện cập nhật Payment Status và phát `payment.completed` Kafka event (nếu thành công), sau đó redirect về Client Web (FE).

### 4.2. ZaloPay API
- **API 4.2.1: Tạo phiên giao dịch ZaloPay**
  - Method: `POST`
  - Endpoint: `/api/payment/zalopay/create`
  - Request Body: `PaymentRequest`
  - Response (200 OK): `CreatePaymentResponse`

- **API 4.2.2: ZaloPay Webhook / Callback** (Server - Server)
  - Method: `POST`
  - Endpoint: `/api/payment/zalopay/callback`
  - Request: JSON request theo chuẩn Webhook của ZaloPay chứa `data` và `mac`. Cập nhật Payment Status và Publish `payment.completed` event.
  - Response: Cấu trúc riêng trả về cho ZaloPay `{"returnCode":1, "returnMessage":"success"}`

- **API 4.2.3: ZaloPay Redirect Return**
  - Method: `GET`
  - Endpoint: `/api/payment/zalopay/return`
  - Redirect về cho giao diện Frontend.

### 4.3. MoMo API
- Tương tự có `/api/payment/momo/create`, `/momo/callback` (POST server-server) và `/momo/return` (GET để Frontend redirect). (Callback sẽ Publish `payment.completed` event kafka).

---

## 5. CÁC DEPENDENCY TỚI SERVICE KHÁC

Thực sự `payment-service` KHÔNG GỌI TRỰC TIẾP API của các C-Service khác. Nó hoạt động theo mô hình **Event-Driven / Publisher**. Do đó phụ thuộc của nó được hiển thị qua Event Kafka. Bất kỳ service nào muốn xác nhận thanh toán thành công (ví dụ `order-service`) đều phải consume sự kiện này.

### 1. ORDER SERVICE @order-team
**Event 1.1: Lắng nghe sự kiện Payment Completed (Dùng cho việc chốt đơn)**

- **Mục đích:** Khi web hook từ VNPay/ZaloPay/MoMo báo về `payment-service` thành công, `payment-service` sẽ push sự kiện này. Order Service cần consume topic này để đổi trạng thái đơn hàng từ `PENDING_PAYMENT` -> `PAID`/`PROCESSING`.
- **Giao thức:** Kafka Messaging
- **Topic:** `payment.completed`
- **Message Payload (JSON):**
```json
{
  "orderId": "123e4567-e89b-12d3-a456-426614174000",
  "paymentId": 12,
  "paymentMethod": "VNPAY",
  "status": "COMPLETED"
}
```
=> *(Order service bắt được Kafka payload này, sẽ dùng `orderId` để query database order, và cập nhật status sang Đã Thanh Toán).*

### 2. API GATEWAY SERVICE @gateway-team
**Config 2.1: Route config cho payment-service**
- **Mục đích:** Khi Frontend gọi `/api/payment/...`, API Gateway phải định tuyến đúng về service `bookstore-payment-service`.
- **Cấu hình Gateway yêu cầu:** Mapping path `/api/payment/**` tới host `lb://bookstore-payment-service`. Đồng thời lưu ý không yêu cầu Authentication Token (hoặc cho pass filter) với các endpoint `/callback` hay `/return` vì các hệ thống gateway thứ 3 như (VNPay, ZaloPay) sẽ gửi HTTP không mang Bearer token user.
