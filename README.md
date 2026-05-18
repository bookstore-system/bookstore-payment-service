# Bookstore Payment Service

## 1. Tổng quan

- **Tên service:** `bookstore-payment-service`
- **Package:** `com.notfound.paymentservice`
- **Mục đích:** Xử lý giao dịch thanh toán qua VNPay, ZaloPay, MoMo. Sau khi thanh toán thành công, gửi event `payment.completed` qua **RabbitMQ** để `order-service` cập nhật trạng thái đơn hàng.
- **Port:** `8085` (host), `8080` (container)
- **Database:** `bookstore_payment` (MySQL 8.0)

---

## 2. Khởi động

### Docker Compose (khuyến nghị)

```bash
cd bookstore-payment-service
docker compose -f docker-compose.dev.yml up -d
```

### Local (cần MySQL running)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=default
```

### Cấu hình bắt buộc (`application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/bookstore_payment
    username: bookstore
    password: bookstore
  rabbitmq:
    host: localhost
    port: 5672

payment:
  vnPay:
    url: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
    returnUrl: http://localhost:8085/api/v1/payment/vnpay/callback
    tmnCode: <TMN_CODE>
    secretKey: <SECRET_KEY>
    version: "2.1.0"
    command: pay
    orderType: other
  momo:
    partnerCode: <PARTNER_CODE>
    accessKey: <ACCESS_KEY>
    secretKey: <SECRET_KEY>
    endpoint: https://test-payment.momo.vn/v2/gateway/api/create
    returnUrl: http://localhost:8085/api/v1/payment/momo/return
    ipnUrl: http://localhost:8085/api/v1/payment/momo/callback
  zaloPay:
    appId: <APP_ID>
    key1: <KEY1>
    key2: <KEY2>
    endpoint: https://sb-openapi.zalopay.vn/v2/create
    returnUrl: http://localhost:8085/api/v1/payment/zalopay/return
    callbackUrl: http://localhost:8085/api/v1/payment/zalopay/callback

frontend:
  url: http://localhost:3000
```

---

## 3. Cấu trúc package

```
com.notfound.paymentservice
├── controller/          # PaymentController
├── service/             # VNPayService, ZaloPayService, MoMoService (interfaces)
│   └── impl/            # VNPayServiceImpl, ZaloPayServiceImpl, MoMoServiceImpl
├── repository/          # PaymentRepository
├── model/
│   ├── entity/          # Payment
│   ├── dto/
│   │   ├── request/     # PaymentRequest, VNPayCallbackRequest, MoMoCallbackRequest, ZaloPayCallbackRequest
│   │   └── response/    # ApiResponse, PaymentResponse, CreatePaymentResponse
│   └── enums/           # PaymentMethod, PaymentStatus
├── config/              # VNPayConfig, MoMoConfig, ZaloPayConfig, RabbitMQConfig
├── messaging/           # PaymentMessageProducer, PaymentCompletedEvent
├── util/                # VNPayUtil, MoMoUtil, ZaloPayUtil, HMACUtil
└── exception/           # GlobalExceptionHandler, AppException, ErrorCode
```

---

## 4. Entity & DTO

### Entity `Payment`

| Field | Type | Mô tả |
|---|---|---|
| `paymentID` | UUID (PK) | Auto-generated |
| `orderId` | UUID | Map sang order-service |
| `amount` | Long | Số tiền (VND) |
| `paymentMethod` | String | VNPAY / ZALOPAY / MOMO |
| `status` | Enum | PENDING / COMPLETED / FAILED / REFUNDED |
| `transactionId` | String | ID giao dịch từ payment gateway |
| `redirectUrl` | String | URL redirect sau thanh toán |
| `date` | LocalDateTime | Thời điểm tạo |

### Request `PaymentRequest`

```json
{
  "orderId": "uuid",
  "amount": 150000,
  "orderInfo": "Thanh toan don hang abc",
  "bankCode": "NCB",
  "redirectUrl": "https://frontend.domain/result"
}
```

---

## 5. API

Base path: `/api/v1/payment`

### VNPay

| Method | Endpoint | Mô tả |
|---|---|---|
| POST | `/vnpay/create` | Tạo payment URL → trả `{ paymentUrl }` |
| GET | `/vnpay/callback` | VNPay redirect về, cập nhật status, publish event, redirect FE |

### ZaloPay

| Method | Endpoint | Mô tả |
|---|---|---|
| POST | `/zalopay/create` | Tạo order transaction ZaloPay |
| POST | `/zalopay/callback` | Webhook server-to-server từ ZaloPay |
| GET | `/zalopay/return` | Redirect về FE sau thanh toán |

### MoMo

| Method | Endpoint | Mô tả |
|---|---|---|
| POST | `/momo/create` | Tạo payment URL MoMo |
| POST | `/momo/callback` | IPN server-to-server từ MoMo |
| GET | `/momo/return` | Redirect về FE sau thanh toán |

> **Lưu ý Gateway:** Các endpoint `/callback` và `/return` phải được whitelist tại API Gateway (không yêu cầu Bearer token vì payment gateway bên thứ 3 gọi trực tiếp).

---

## 6. Messaging (RabbitMQ)

| | Giá trị |
|---|---|
| Exchange | `payment.exchange` |
| Routing key (completed) | `payment.completed` |
| Routing key (failed) | `payment.failed` |
| Consumer | `order-service` |

**Payload `PaymentCompletedEvent`:**

```json
{
  "orderId": "uuid",
  "paymentId": "uuid",
  "paymentMethod": "VNPAY",
  "status": "COMPLETED"
}
```

### Khi nào publish event

| Gateway | Trigger COMPLETED | Trigger FAILED |
|---|---|---|
| **VNPay** | `handleVNPayReturn` — signature valid + `isSuccess()` | `handleVNPayReturn` — signature valid + `!isSuccess()` |
| **MoMo** | `handleMoMoCallback` — signature valid + `resultCode == 0` | `handleMoMoCallback` — signature valid + `resultCode != 0` (null-safe) |
| **ZaloPay** | `handleCallback` — MAC valid (ZaloPay chỉ IPN khi success) | `/zalopay/return` — `status != 1` → gọi `markPaymentFailed(appTransId)` |

Idempotency: callback chỉ xử lý nếu `Payment.status == PENDING`. Status đã chuyển → bỏ qua, không publish lại.

---

## 7. Unit Tests

### Chạy tests (không cần DB / RabbitMQ)

```bash
./mvnw test "-Dtest=VNPayServiceImplTest,MoMoServiceImplTest,ZaloPayServiceImplTest,PaymentControllerTest,VNPayCallbackRequestTest"
```

### Danh sách test files

| File | Tests | Phạm vi |
|---|---|---|
| `service/VNPayServiceImplTest` | 8 | createPaymentUrl, handleReturn (sig invalid, not found, already done, success, failed), getRedirectUrl |
| `service/MoMoServiceImplTest` | 7 | handleCallback (sig invalid, not found, already done, success, failed), getRedirectUrl |
| `service/ZaloPayServiceImplTest` | 6 | handleCallback (MAC mismatch, not found, already done, success), getRedirectUrl |
| `controller/PaymentControllerTest` | 8 | Tất cả endpoints VNPay/ZaloPay/MoMo, redirect fallback |
| `util/VNPayCallbackRequestTest` | 11 | `isSuccess()`, `getAmountInVND()`, `getResponseMessage()` |

**Kết quả:** 40 tests, 0 failures

### Lưu ý khi viết tests cho SB4

- `@WebMvcTest` và `@MockBean` **đã bị xóa** trong SB4 — dùng `MockMvcBuilders.standaloneSetup()` thay thế
- `@MockitoBean` (Spring Framework 7) thay `@MockBean` nếu cần Spring context
- Static method mock dùng `Mockito.mockStatic()` (Mockito 5.x, không cần dependency thêm)

---

## 8. Known Issues

| # | Vấn đề | Trạng thái |
|---|---|---|
| Bug-001 | `VNPayCallbackRequest.getAmountInVND()` không handle null — ném NPE thay `NumberFormatException` | Open — null không phải case hợp lệ từ VNPay |
| Bug-002 | README cũ ghi sai messaging là Kafka — thực tế dùng RabbitMQ | Fixed (2026-05-15) |
| Bug-003 | README cũ ghi sai endpoint prefix `/api/payment/` — thực tế là `/api/v1/payment/` | Fixed (2026-05-15) |
| Bug-004 | VNPay/MoMo handler fail không publish `payment.failed` → order kẹt PENDING | Fixed (2026-05-16) |
| Bug-005 | ZaloPay return status != 1 không update Payment.status & không publish failed event | Fixed (2026-05-16) — `markPaymentFailed(appTransId)` |
| Bug-006 | MoMo `resultCode == 0` NPE khi callback gửi null | Fixed (2026-05-16) |
| Bug-007 | Redirect URL build bằng string concat + `URLEncoder` thủ công | Fixed (2026-05-16) — `UriComponentsBuilder` |
| Bug-008 | `MoMoServiceImpl` `new RestTemplate()` / `new ObjectMapper()` thay vì `@Bean` | Open (Phase 2) |
| Bug-009 | Callback handler race condition (no DB lock) — 2 callback đồng thời cùng đọc PENDING | Open (Phase 3) |
| Bug-010 | `Payment` entity thiếu `userId` → khó audit | Open (Phase 3) |

## 9. Changelog

### 2026-05-16 — Phase 1 fixes
- **H2** Publish `payment.failed` event ở 3 gateway (VNPay/MoMo/ZaloPay) → consumer order-service nhận event để cancel order
- **M3** Null-safe `resultCode == 0` MoMo callback
- **L6** 3 redirect URL build dùng `UriComponentsBuilder.queryParam(...).build().encode().toUriString()`
- Thêm method `ZaloPayService.markPaymentFailed(appTransId)` — gọi từ controller `/zalopay/return` khi status != 1
- Test cập nhật: 2 test fail behavior cũ → assert event sent
