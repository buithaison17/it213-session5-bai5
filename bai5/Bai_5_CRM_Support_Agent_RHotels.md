# BÀI 5: SÁNG TẠO NÂNG CAO
## Thiết kế Trợ lý ảo tra cứu CRM & Áp dụng Voucher tự động

> **Bối cảnh:** R-Hotels xây dựng CRM Support Agent bằng Spring AI. Agent có khả năng đọc ngữ cảnh hội thoại để xác định Customer ID, tra cứu voucher, tự động lựa chọn voucher tốt nhất và áp dụng voucher vào hóa đơn.

---

## 1. Mô tả bối cảnh & mục tiêu

### 1.1. Bối cảnh nghiệp vụ

Khách hàng có thể yêu cầu:

> "Áp dụng giúp tôi mã giảm giá tốt nhất của tôi vào đơn đặt phòng mã HD999 nhé."

Để thực hiện yêu cầu, AI Agent phải thực hiện nhiều bước:

1. Xác định khách hàng từ lịch sử hội thoại (`ChatMemory`).
2. Nếu chưa có Customer ID thì yêu cầu khách hàng cung cấp.
3. Gọi `getCustomerVouchers(customerId)` để lấy voucher còn hiệu lực.
4. Phân tích danh sách voucher và lựa chọn voucher có mức giảm tốt nhất.
5. Gọi `applyVoucherToInvoice(invoiceId, voucherCode)`.
6. Cập nhật database nếu nghiệp vụ hợp lệ.
7. Trả kết quả tự nhiên cho khách hàng.

Điểm quan trọng của bài toán là đây không còn là chatbot chỉ trả lời văn bản. Đây là một **AI Agent có khả năng thực hiện nghiệp vụ thông qua Tool Calling**.

### 1.2. Mục tiêu kỹ thuật

Giải pháp cần đảm bảo:

- `ChatMemory` giữ được danh tính khách hàng giữa nhiều lượt chat.
- Tool chỉ nhận dữ liệu cần thiết và có mô tả rõ ràng.
- Java Service kiểm tra dữ liệu đầu vào trước khi truy cập database.
- Không để lỗi nghiệp vụ làm crash request.
- Không cho AI tự ý suy đoán Customer ID hoặc voucher.
- Chỉ áp dụng voucher sau khi hệ thống backend xác nhận điều kiện nghiệp vụ.
- Có thể thực hiện chuỗi Tool Calling:
  - Tool 1: lấy voucher.
  - AI phân tích kết quả.
  - Tool 2: áp dụng voucher.
- Database transaction phải được kiểm soát ở tầng Service.

---

# 2. Kiến trúc giải pháp

Luồng tổng quát:

```text
+------------------+
|    Customer      |
| "Áp dụng voucher |
|  cho HD999"      |
+--------+---------+
         |
         v
+----------------------------+
| REST Controller            |
| POST /api/chat             |
+-------------+--------------+
              |
              v
+----------------------------+
| ChatMemory / Conversation  |
| Kiểm tra lịch sử hội thoại |
+-------------+--------------+
              |
              | Có Customer ID?
       +------+------+
       |             |
      NO            YES
       |             |
       v             v
+-------------+   +---------------------------+
| Hỏi Customer|   | Spring AI ChatClient      |
| ID / phone  |   | + System Prompt           |
+-------------+   +-------------+-------------+
                                 |
                                 v
                    +---------------------------+
                    | AI quyết định gọi Tool 1 |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | Tool 1                    |
                    | getCustomerVouchers()     |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | CRM / Database            |
                    | Voucher còn hiệu lực      |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | Kết quả Tool 1             |
                    | VIP20 - 20%                |
                    | WELCOME10 - 10%            |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | LLM phân tích              |
                    | Chọn VIP20                 |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | Tool 2                    |
                    | applyVoucherToInvoice()   |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | Invoice Service            |
                    | Kiểm tra trạng thái HD     |
                    | Kiểm tra voucher          |
                    +-------------+-------------+
                                  |
                     +------------+------------+
                     |                         |
                  INVALID                    VALID
                     |                         |
                     v                         v
             +---------------+       +----------------+
             | Business Error|       | UPDATE DB      |
             +-------+-------+       +-------+--------+
                     |                       |
                     +-----------+-----------+
                                 |
                                 v
                    +---------------------------+
                    | AI tổng hợp kết quả       |
                    +-------------+-------------+
                                  |
                                  v
                    +---------------------------+
                    | Response cho Customer     |
                    +---------------------------+
```

---

# 3. ASCII Flow Diagram chi tiết

```text
┌───────────────────────────────┐
│ 1. CUSTOMER                   │
│ "Áp dụng voucher tốt nhất     │
│  cho hóa đơn HD999"           │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│ 2. REST CONTROLLER            │
│ POST /api/chat                 │
│ conversationId + message      │
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│ 3. CHAT MEMORY                 │
│ Đọc lịch sử conversation       │
│                                │
│ Ví dụ:                         │
│ "Customer ID của tôi là       │
│  KH888"                        │
└───────────────┬───────────────┘
                │
                ▼
        ┌───────────────┐
        │ Có CustomerID?│
        └───────┬───────┘
          NO    │    YES
        ┌───────┘      └──────────────┐
        ▼                             ▼
┌─────────────────┐       ┌─────────────────────────┐
│ Hỏi Customer ID │       │ 4. AI gọi Tool 1        │
│ hoặc số điện    │       │ getCustomerVouchers()   │
│ thoại            │       └────────────┬────────────┘
└─────────────────┘                    │
                                       ▼
                           ┌─────────────────────────┐
                           │ CRM / Voucher DB        │
                           │                         │
                           │ VIP20       20%          │
                           │ WELCOME10   10%          │
                           └────────────┬────────────┘
                                        │
                                        ▼
                           ┌─────────────────────────┐
                           │ 5. TOOL RESULT           │
                           │ List<CustomerVoucher>   │
                           └────────────┬────────────┘
                                        │
                                        ▼
                           ┌─────────────────────────┐
                           │ 6. LLM phân tích         │
                           │ Chọn voucher tốt nhất   │
                           │ => VIP20                 │
                           └────────────┬────────────┘
                                        │
                                        ▼
                           ┌─────────────────────────┐
                           │ 7. AI gọi Tool 2         │
                           │ applyVoucherToInvoice()  │
                           │ HD999 + VIP20            │
                           └────────────┬────────────┘
                                        │
                                        ▼
                           ┌─────────────────────────┐
                           │ 8. BUSINESS VALIDATION   │
                           │                         │
                           │ Invoice tồn tại?        │
                           │ Đã thanh toán?           │
                           │ Voucher còn hạn?         │
                           │ Voucher thuộc Customer? │
                           └────────────┬────────────┘
                                        │
                            ┌───────────┴───────────┐
                            │                       │
                          FAIL                    PASS
                            │                       │
                            ▼                       ▼
                  ┌──────────────────┐    ┌──────────────────┐
                  │ Business Error   │    │ UPDATE invoice   │
                  │ Không throw ra   │    │ SAVE DB          │
                  │ ngoài Controller │    └────────┬─────────┘
                  └────────┬─────────┘             │
                           │                       │
                           └───────────┬───────────┘
                                       ▼
                           ┌─────────────────────────┐
                           │ 9. AI tổng hợp           │
                           │ kết quả cho khách hàng  │
                           └────────────┬────────────┘
                                        │
                                        ▼
                           ┌─────────────────────────┐
                           │ 10. CHAT RESPONSE        │
                           │ "Đã áp dụng VIP20..."   │
                           └─────────────────────────┘
```

---

# 4. Thiết kế dữ liệu

## 4.1. CustomerVoucher

```java
package com.example.rhotel.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CustomerVoucher(
        String code,
        BigDecimal discountPercent,
        LocalDateTime expiresAt
) {
}
```

## 4.2. Request của Tool 1

```java
package com.example.rhotel.dto;

public record GetCustomerVouchersRequest(
        String customerId
) {
}
```

## 4.3. Response của Tool 1

```java
package com.example.rhotel.dto;

import java.util.List;

public record GetCustomerVouchersResponse(
        boolean success,
        String message,
        List<CustomerVoucher> vouchers
) {
}
```

## 4.4. Request của Tool 2

```java
package com.example.rhotel.dto;

public record ApplyVoucherRequest(
        String invoiceId,
        String voucherCode
) {
}
```

## 4.5. Response của Tool 2

```java
package com.example.rhotel.dto;

public record ApplyVoucherResponse(
        boolean success,
        String message,
        String invoiceId,
        String voucherCode
) {
}
```

## 4.6. REST Chat Request

```java
package com.example.rhotel.dto;

public record ChatRequest(
        String conversationId,
        String message
) {
}
```

## 4.7. REST Chat Response

```java
package com.example.rhotel.dto;

public record ChatResponse(
        String conversationId,
        String answer
) {
}
```

---

# 5. Repository giả lập

Trong project thực tế, các Repository này có thể thay thế bằng Spring Data JPA Repository.

## 5.1. Invoice

```java
package com.example.rhotel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Invoice {

    @Id
    private String id;

    private String customerId;

    private String appliedVoucherCode;

    private boolean paid;
}
```

## 5.2. Voucher

```java
package com.example.rhotel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class Voucher {

    @Id
    private String code;

    private String customerId;

    private BigDecimal discountPercent;

    private LocalDateTime expiresAt;

    private boolean active;
}
```

## 5.3. Repository

```java
package com.example.rhotel.repository;

import com.example.rhotel.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
}
```

```java
package com.example.rhotel.repository;

import com.example.rhotel.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, String> {

    List<Voucher> findByCustomerIdAndActiveTrueAndExpiresAtAfter(
            String customerId,
            LocalDateTime now
    );
}
```

---

# 6. Service chứa hai AI Tools

Đây là thành phần quan trọng nhất của bài.

Nguyên tắc thiết kế:

- Không tin tưởng tuyệt đối tham số do LLM truyền vào.
- Validate `null`, blank và format trước khi xử lý.
- Không dùng exception để biểu diễn lỗi nghiệp vụ thông thường.
- Không cho phép áp dụng voucher nếu invoice đã thanh toán.
- Kiểm tra voucher tồn tại.
- Kiểm tra voucher thuộc đúng Customer.
- Kiểm tra voucher còn hạn.
- Trả về `Response DTO` an toàn cho LLM.

```java
package com.example.rhotel.service;

import com.example.rhotel.dto.ApplyVoucherRequest;
import com.example.rhotel.dto.ApplyVoucherResponse;
import com.example.rhotel.dto.CustomerVoucher;
import com.example.rhotel.dto.GetCustomerVouchersResponse;
import com.example.rhotel.entity.Invoice;
import com.example.rhotel.entity.Voucher;
import com.example.rhotel.repository.InvoiceRepository;
import com.example.rhotel.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CrmVoucherToolService {

    private final VoucherRepository voucherRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * Tool 1:
     * Lấy danh sách voucher còn hiệu lực của khách hàng.
     */
    @Tool(
        name = "getCustomerVouchers",
        description = """
            Tra cứu danh sách voucher còn hiệu lực của một khách hàng
            trong hệ thống CRM.

            Chỉ sử dụng tool này khi đã xác định được Customer ID thực tế
            từ lịch sử hội thoại hoặc thông tin khách hàng do người dùng cung cấp.

            Không tự suy đoán Customer ID.
            """
    )
    public GetCustomerVouchersResponse getCustomerVouchers(
            @ToolParam(description = "Mã khách hàng, ví dụ KH888")
            String customerId
    ) {

        if (customerId == null || customerId.isBlank()) {
            return new GetCustomerVouchersResponse(
                    false,
                    "Thiếu Customer ID. Vui lòng yêu cầu khách hàng cung cấp mã khách hàng.",
                    List.of()
            );
        }

        String normalizedCustomerId = customerId.trim();

        try {
            List<Voucher> vouchers =
                    voucherRepository.findByCustomerIdAndActiveTrueAndExpiresAtAfter(
                            normalizedCustomerId,
                            LocalDateTime.now()
                    );

            List<CustomerVoucher> result = vouchers.stream()
                    .map(v -> new CustomerVoucher(
                            v.getCode(),
                            v.getDiscountPercent(),
                            v.getExpiresAt()
                    ))
                    .sorted(
                            Comparator.comparing(
                                    CustomerVoucher::discountPercent
                            ).reversed()
                    )
                    .toList();

            if (result.isEmpty()) {
                return new GetCustomerVouchersResponse(
                        true,
                        "Khách hàng không có voucher còn hiệu lực.",
                        List.of()
                );
            }

            return new GetCustomerVouchersResponse(
                    true,
                    "Tra cứu voucher thành công.",
                    result
            );

        } catch (Exception ex) {

            /*
             * Không expose stack trace/database error cho LLM.
             */
            return new GetCustomerVouchersResponse(
                    false,
                    "Không thể tra cứu voucher vào lúc này. Vui lòng thử lại sau.",
                    List.of()
            );
        }
    }

    /**
     * Tool 2:
     * Áp dụng voucher vào invoice.
     */
    @Tool(
        name = "applyVoucherToInvoice",
        description = """
            Áp dụng một voucher hợp lệ vào hóa đơn đặt phòng.

            Chỉ sử dụng sau khi đã có invoiceId và voucherCode hợp lệ.
            Tool sẽ tự kiểm tra trạng thái hóa đơn, quyền sở hữu voucher
            và thời hạn voucher.

            Nếu nghiệp vụ không hợp lệ, tool trả về business error an toàn
            thay vì ném exception.
            """
    )
    @Transactional
    public ApplyVoucherResponse applyVoucherToInvoice(
            @ToolParam(description = "Mã hóa đơn, ví dụ HD999")
            String invoiceId,

            @ToolParam(description = "Mã voucher cần áp dụng, ví dụ VIP20")
            String voucherCode
    ) {

        if (invoiceId == null || invoiceId.isBlank()) {
            return new ApplyVoucherResponse(
                    false,
                    "Thiếu mã hóa đơn.",
                    invoiceId,
                    voucherCode
            );
        }

        if (voucherCode == null || voucherCode.isBlank()) {
            return new ApplyVoucherResponse(
                    false,
                    "Thiếu mã voucher.",
                    invoiceId,
                    voucherCode
            );
        }

        String normalizedInvoiceId = invoiceId.trim();
        String normalizedVoucherCode = voucherCode.trim();

        try {

            Invoice invoice = invoiceRepository
                    .findById(normalizedInvoiceId)
                    .orElse(null);

            if (invoice == null) {
                return new ApplyVoucherResponse(
                        false,
                        "Không tìm thấy hóa đơn " + normalizedInvoiceId + ".",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            if (invoice.isPaid()) {
                return new ApplyVoucherResponse(
                        false,
                        "Không thể áp dụng voucher vì hóa đơn đã được thanh toán.",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            Voucher voucher = voucherRepository
                    .findById(normalizedVoucherCode)
                    .orElse(null);

            if (voucher == null) {
                return new ApplyVoucherResponse(
                        false,
                        "Voucher không tồn tại.",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            if (!voucher.isActive()) {
                return new ApplyVoucherResponse(
                        false,
                        "Voucher hiện không còn hiệu lực.",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            if (voucher.getExpiresAt() == null ||
                    voucher.getExpiresAt().isBefore(LocalDateTime.now())) {

                return new ApplyVoucherResponse(
                        false,
                        "Voucher đã hết hạn.",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            /*
             * Đây là lớp phòng thủ quan trọng:
             * voucher phải thuộc đúng customer của invoice.
             */
            if (!invoice.getCustomerId().equals(voucher.getCustomerId())) {

                return new ApplyVoucherResponse(
                        false,
                        "Voucher không thuộc khách hàng của hóa đơn.",
                        normalizedInvoiceId,
                        normalizedVoucherCode
                );
            }

            invoice.setAppliedVoucherCode(voucher.getCode());

            invoiceRepository.save(invoice);

            return new ApplyVoucherResponse(
                    true,
                    "Áp dụng voucher thành công.",
                    normalizedInvoiceId,
                    normalizedVoucherCode
            );

        } catch (Exception ex) {

            /*
             * Không để exception kỹ thuật phá vỡ cuộc hội thoại.
             * Log thực tế nên được thực hiện bằng Logger.
             */
            return new ApplyVoucherResponse(
                    false,
                    "Không thể áp dụng voucher vào lúc này. Vui lòng thử lại sau.",
                    normalizedInvoiceId,
                    normalizedVoucherCode
            );
        }
    }
}
```

---

# 7. Vì sao Tool phải trả Business Error thay vì throw Exception?

Có hai loại lỗi cần phân biệt.

### 7.1. Lỗi nghiệp vụ

Ví dụ:

```text
Invoice đã thanh toán
Voucher hết hạn
Voucher không thuộc khách hàng
Invoice không tồn tại
Voucher không tồn tại
```

Đây là các tình huống có thể dự đoán trước.

Không nên:

```java
throw new RuntimeException("Invoice already paid");
```

Vì exception có thể làm flow tool calling thất bại và khiến controller phải xử lý lỗi không cần thiết.

Thay vào đó:

```java
return new ApplyVoucherResponse(
    false,
    "Không thể áp dụng voucher vì hóa đơn đã được thanh toán.",
    invoiceId,
    voucherCode
);
```

LLM nhận được một kết quả có cấu trúc và có thể diễn đạt lại cho khách hàng.

### 7.2. Lỗi kỹ thuật

Ví dụ:

```text
Database connection timeout
CRM service unavailable
Network error
```

Cũng cần bắt ở boundary phù hợp và trả thông báo an toàn.

Tuy nhiên hệ thống thực tế vẫn phải:

- Log lỗi đầy đủ ở backend.
- Theo dõi bằng monitoring.
- Không gửi stack trace cho LLM.
- Không gửi thông tin database nội bộ cho khách hàng.

---

# 8. Prompt cho CRM Support Agent

Prompt nên quy định rõ vai trò, quy trình và giới hạn của Agent.

```text
Bạn là CRM Support Agent của R-Hotels.

NHIỆM VỤ:
Hỗ trợ khách hàng tra cứu voucher và áp dụng voucher vào hóa đơn.

QUY TẮC XÁC ĐỊNH KHÁCH HÀNG:
1. Trước khi gọi getCustomerVouchers, hãy kiểm tra lịch sử hội thoại.
2. Nếu lịch sử hội thoại đã chứa Customer ID do khách hàng cung cấp,
   hãy sử dụng Customer ID đó.
3. Nếu chưa có Customer ID, hãy yêu cầu khách hàng cung cấp Customer ID
   hoặc thông tin định danh phù hợp.
4. Tuyệt đối không tự suy đoán Customer ID.

QUY TRÌNH ÁP DỤNG VOUCHER:
1. Xác định invoiceId từ yêu cầu khách hàng.
2. Xác định Customer ID.
3. Gọi getCustomerVouchers(customerId).
4. Từ kết quả tool, lựa chọn voucher có discountPercent cao nhất.
5. Không tự tạo voucher code.
6. Gọi applyVoucherToInvoice(invoiceId, voucherCode).
7. Chỉ thông báo "đã áp dụng thành công" khi kết quả Tool 2
   xác nhận success=true.
8. Nếu Tool 2 trả success=false, giải thích đúng message của tool
   và không nói rằng voucher đã được áp dụng.

QUY TẮC AN TOÀN:
- Không tự bịa Customer ID.
- Không tự bịa voucher.
- Không nói database đã cập nhật nếu Tool 2 chưa trả thành công.
- Không tiết lộ exception, stack trace hoặc thông tin nội bộ.
```

---

# 9. ChatMemory và xác định Customer ID

Một conversation có thể diễn ra như sau:

```text
User:
Customer ID của tôi là KH888.

Assistant:
Đã ghi nhận Customer ID KH888.

User:
Áp dụng voucher tốt nhất cho HD999.
```

Ở lượt thứ hai, Agent phải tận dụng `ChatMemory`.

Có thể lưu conversation theo:

```text
conversationId = "conversation-001"
```

và sử dụng cùng conversation ID cho các request tiếp theo.

---

# 10. REST Controller tích hợp ChatMemory

Ví dụ sử dụng Spring AI `ChatClient` kết hợp `MessageChatMemoryAdvisor`.

> API constructor/DSL của ChatMemory có thể khác nhẹ giữa các phiên bản Spring AI. Phần cốt lõi cần giữ nguyên là: conversation ID được dùng làm khóa memory, và cùng memory được truyền vào ChatClient ở mỗi lượt.

```java
package com.example.rhotel.controller;

import com.example.rhotel.dto.ChatRequest;
import com.example.rhotel.dto.ChatResponse;
import com.example.rhotel.service.CrmVoucherToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatClient.Builder chatClientBuilder;
    private final CrmVoucherToolService crmVoucherToolService;

    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {

        ToolCallback[] tools = MethodToolCallbackProvider
                .builder()
                .toolObjects(crmVoucherToolService)
                .build()
                .getToolCallbacks();

        String answer = chatClientBuilder.build()
                .prompt()
                .system("""
                    Bạn là CRM Support Agent của R-Hotels.

                    Hãy kiểm tra lịch sử hội thoại trước khi yêu cầu
                    khách hàng cung cấp lại Customer ID.

                    Nếu đã xác định Customer ID:
                    - Tra cứu voucher bằng getCustomerVouchers.
                    - Chọn voucher có discountPercent cao nhất.
                    - Sau đó dùng applyVoucherToInvoice để áp dụng.

                    Không tự tạo Customer ID hoặc voucher code.

                    Chỉ xác nhận áp dụng thành công khi Tool 2 trả success=true.

                    Nếu tool trả business error, hãy giải thích chính xác
                    lý do cho khách hàng bằng ngôn ngữ tự nhiên.
                    """)
                .user(request.message())
                .tools(tools)
                .advisors(advisor -> advisor
                        .param(
                                MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                                request.conversationId()
                        )
                        .param(
                                MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY,
                                20
                        )
                )
                .advisors(new MessageChatMemoryAdvisor(chatMemory))
                .call()
                .content();

        return new ChatResponse(
                request.conversationId(),
                answer
        );
    }
}
```

---

# 11. Cấu hình ChatMemory tốt hơn trong production

Trong ví dụ trên, `MessageWindowChatMemory` chỉ phù hợp để minh họa.

Production nên dùng persistent memory.

Ví dụ:

```text
POST /api/chat
        |
        v
conversationId
        |
        v
Persistent ChatMemory
        |
        +---- Redis
        |
        +---- PostgreSQL
        |
        +---- MongoDB
```

Mục tiêu là khi ứng dụng restart, Agent vẫn có thể khôi phục lịch sử hội thoại.

Một thiết kế tốt hơn là:

```text
Conversation
    |
    +-- conversationId
    |
    +-- customerId
    |
    +-- messages[]
```

Trong đó `customerId` có thể được lưu như conversation metadata sau khi đã xác minh.

Tuy nhiên, không nên chỉ dựa vào memory cho authorization. Khi Tool 2 thực hiện thay đổi database, backend vẫn phải kiểm tra voucher thuộc đúng customer của invoice.

---

# 12. Tool Chaining hoạt động như thế nào?

Đây là phần quan trọng nhất về mặt kỹ thuật.

Spring AI không phải lúc nào cũng trực tiếp gọi:

```text
Tool 1 -> Tool 2
```

theo kiểu Java:

```java
var vouchers = getCustomerVouchers(customerId);
applyVoucherToInvoice(invoiceId, vouchers.get(0).code());
```

Thay vào đó, Agent Loop thường hoạt động theo mô hình:

```text
User Message
     |
     v
    LLM
     |
     | Tool Call #1
     v
getCustomerVouchers("KH888")
     |
     v
Tool Result
[
  VIP20 = 20%,
  WELCOME10 = 10%
]
     |
     v
LLM nhận Tool Result
     |
     | Reasoning / lựa chọn
     v
Tool Call #2
applyVoucherToInvoice("HD999", "VIP20")
     |
     v
Tool Result
success = true
     |
     v
LLM
     |
     v
Final Answer
```

---

# 13. LLM lấy kết quả Tool 1 để truyền vào Tool 2 như thế nào?

Điểm quan trọng:

**Java không cần tự viết biến trung gian để truyền kết quả Tool 1 sang Tool 2 trong trường hợp Agent Tool Calling thông thường.**

Ví dụ Tool 1 trả:

```json
{
  "success": true,
  "message": "Tra cứu voucher thành công.",
  "vouchers": [
    {
      "code": "VIP20",
      "discountPercent": 20
    },
    {
      "code": "WELCOME10",
      "discountPercent": 10
    }
  ]
}
```

Kết quả này được đưa trở lại context/tool result của model.

Model nhìn thấy:

```text
Customer ID = KH888

Available vouchers:
VIP20       = 20%
WELCOME10   = 10%
```

Sau đó model tạo Tool Call tiếp theo:

```json
{
  "name": "applyVoucherToInvoice",
  "arguments": {
    "invoiceId": "HD999",
    "voucherCode": "VIP20"
  }
}
```

Spring AI nhận Tool Call này và thực thi method Java tương ứng.

---

# 14. Tại sao Tool Description rất quan trọng?

Tool description chính là metadata giúp LLM hiểu:

- Tool dùng để làm gì.
- Khi nào nên gọi.
- Không nên gọi trong trường hợp nào.
- Ý nghĩa của từng tham số.

Ví dụ tốt:

```java
@Tool(
    name = "applyVoucherToInvoice",
    description = """
        Áp dụng voucher hợp lệ vào hóa đơn đặt phòng.
        Chỉ gọi sau khi đã xác định invoiceId và voucherCode.
        Tool tự kiểm tra hóa đơn đã thanh toán chưa,
        voucher có tồn tại, còn hạn và thuộc đúng khách hàng hay không.
        """
)
```

Model sẽ có nhiều thông tin hơn để quyết định đúng thời điểm gọi tool.

---

# 15. Không nên để LLM quyết định business validation

Một lỗi thiết kế nguy hiểm là:

```text
LLM:
"Voucher VIP20 hợp lệ."

Backend:
UPDATE invoice SET voucher = 'VIP20'
```

Điều này không an toàn.

LLM chỉ nên **đề xuất hành động**.

Backend mới là nơi **quyết định hành động có hợp lệ hay không**.

Kiến trúc đúng:

```text
             LLM
              |
              | "Tôi muốn áp dụng VIP20"
              v
       +--------------+
       | Backend Tool |
       +------+-------+
              |
       Validate business
              |
       +------+------+
       |             |
      FAIL          PASS
       |             |
       v             v
   Error DTO      UPDATE DB
```

Đây là nguyên tắc:

> **LLM decides what to try; backend decides what is allowed.**

---

# 16. Phân tích chịu lỗi

## 16.1. Customer ID bị thiếu

Input:

```text
Áp dụng voucher tốt nhất cho HD999.
```

Memory:

```text
Không có Customer ID.
```

Agent không được gọi:

```text
getCustomerVouchers(null)
```

Mà phải hỏi:

```text
Để kiểm tra voucher của bạn, vui lòng cung cấp mã khách hàng
hoặc thông tin định danh đã đăng ký với R-Hotels.
```

---

## 16.2. Customer không có voucher

Tool 1:

```json
{
  "success": true,
  "message": "Khách hàng không có voucher còn hiệu lực.",
  "vouchers": []
}
```

Agent không được gọi Tool 2.

Response:

```text
Hiện tại tài khoản của bạn không có voucher còn hiệu lực
để áp dụng cho hóa đơn HD999.
```

---

## 16.3. Invoice đã thanh toán

Tool 2:

```json
{
  "success": false,
  "message": "Không thể áp dụng voucher vì hóa đơn đã được thanh toán."
}
```

Agent:

```text
Hóa đơn HD999 đã được thanh toán nên hệ thống không thể áp dụng voucher.
```

Không được trả:

```text
Đã áp dụng VIP20 thành công.
```

---

## 16.4. Voucher hết hạn

Backend trả:

```json
{
  "success": false,
  "message": "Voucher đã hết hạn."
}
```

Agent phải sử dụng chính kết quả đó để phản hồi.

---

## 16.5. Voucher không thuộc khách hàng

Đây là lớp bảo mật nghiệp vụ rất quan trọng.

Giả sử:

```text
Invoice HD999
Customer = KH888

Voucher VIP20
Customer = KH777
```

Cho dù LLM yêu cầu:

```text
applyVoucherToInvoice("HD999", "VIP20")
```

backend phải từ chối:

```text
Voucher không thuộc khách hàng của hóa đơn.
```

Không được tin rằng vì Tool 1 trả về voucher thì Tool 2 mặc nhiên được phép cập nhật.

---

# 17. Một vấn đề quan trọng: chọn "voucher tốt nhất"

Nếu business rule đơn giản là:

```text
discountPercent cao nhất
```

có thể xử lý trực tiếp ở backend:

```java
CustomerVoucher best = vouchers.stream()
        .max(Comparator.comparing(CustomerVoucher::discountPercent))
        .orElse(null);
```

Tuy nhiên đề bài yêu cầu AI tự động phân tích và chọn voucher.

Khi đó Tool 1 trả danh sách:

```text
VIP20       20%
WELCOME10   10%
```

LLM chọn:

```text
VIP20
```

Sau đó gọi Tool 2.

Nếu business rule phức tạp hơn:

```text
VIP20: giảm 20%, tối đa 500.000đ
SALE15: giảm 15%, không giới hạn
```

thì không nên để LLM tự tính toàn bộ logic tài chính.

Nên tạo một domain service:

```text
VoucherRecommendationService
```

để tính toán chính xác, còn LLM chỉ chịu trách nhiệm giao tiếp và điều phối.

---

# 18. Sequence Diagram dạng ASCII

```text
Customer
   |
   | "Áp dụng voucher tốt nhất cho HD999"
   |
   v
Controller
   |
   | conversationId
   v
ChatMemory
   |
   | Customer ID = KH888
   v
ChatClient
   |
   | Tool Call #1
   v
getCustomerVouchers
   |
   v
VoucherRepository
   |
   | [VIP20, WELCOME10]
   v
getCustomerVouchers
   |
   | Tool Result
   v
ChatClient / LLM
   |
   | Chọn VIP20
   |
   | Tool Call #2
   v
applyVoucherToInvoice
   |
   +------> InvoiceRepository
   |
   +------> VoucherRepository
   |
   | validation OK
   |
   | UPDATE invoice
   v
Database
   |
   | success=true
   v
ChatClient / LLM
   |
   | Final response
   v
Controller
   |
   v
Customer
```

---

# 19. Luồng thực tế với dữ liệu mẫu

## Request 1

```json
{
  "conversationId": "conv-001",
  "message": "Customer ID của tôi là KH888."
}
```

Memory lưu:

```text
Customer ID = KH888
```

## Request 2

```json
{
  "conversationId": "conv-001",
  "message": "Áp dụng voucher tốt nhất vào HD999 nhé."
}
```

LLM đọc memory:

```text
Customer ID = KH888
Invoice = HD999
```

Tool 1:

```text
getCustomerVouchers("KH888")
```

Kết quả:

```json
{
  "success": true,
  "message": "Tra cứu voucher thành công.",
  "vouchers": [
    {
      "code": "VIP20",
      "discountPercent": 20
    },
    {
      "code": "WELCOME10",
      "discountPercent": 10
    }
  ]
}
```

LLM chọn:

```text
VIP20
```

Tool 2:

```text
applyVoucherToInvoice("HD999", "VIP20")
```

Kết quả:

```json
{
  "success": true,
  "message": "Áp dụng voucher thành công.",
  "invoiceId": "HD999",
  "voucherCode": "VIP20"
}
```

Final answer:

```text
Đã áp dụng thành công voucher VIP20 giảm 20% vào hóa đơn HD999 của bạn.
```

---

# 20. Ưu điểm của kiến trúc

## 20.1. Tách biệt AI và Business Logic

LLM:

```text
Hiểu ngôn ngữ
Điều phối Tool
Lựa chọn hành động
```

Backend:

```text
Validation
Authorization nghiệp vụ
Transaction
Database
```

## 20.2. Chịu lỗi tốt

Business error trở thành DTO:

```text
success=false
message=...
```

thay vì làm crash application.

## 20.3. Có thể mở rộng

Sau này có thể thêm:

```text
getCustomerProfile()
getCustomerBookings()
getCustomerPoints()
calculateBestVoucher()
cancelVoucher()
```

Agent có thể lựa chọn Tool phù hợp dựa trên metadata.

---

# 21. Các nguyên tắc cần nhấn mạnh khi bảo vệ bài

### Nguyên tắc 1

**ChatMemory dùng để duy trì context, không phải để thay thế authorization.**

### Nguyên tắc 2

**LLM không được tin tưởng tuyệt đối.**

Mọi dữ liệu quan trọng phải được backend validate.

### Nguyên tắc 3

**Tool phải có contract rõ ràng.**

Input:

```text
invoiceId
voucherCode
```

Output:

```text
success
message
```

### Nguyên tắc 4

**Business error không nên trở thành application crash.**

Ví dụ:

```text
Invoice đã thanh toán
```

là business result, không phải lỗi hệ thống.

### Nguyên tắc 5

**Chỉ xác nhận thành công sau khi Tool 2 trả success=true.**

### Nguyên tắc 6

**Tool chaining dựa trên vòng lặp Agent của LLM.**

Không nên hiểu đơn giản rằng Spring AI tự động nối trực tiếp:

```java
Tool1Result -> Tool2Parameter
```

Thực tế:

```text
Tool 1
  ↓
Tool Result đưa vào context
  ↓
LLM phân tích
  ↓
LLM tạo Tool Call 2
  ↓
Spring AI thực thi Tool 2
```

---

# 22. Kết luận

Giải pháp CRM Support Agent sử dụng Spring AI giúp R-Hotels chuyển chatbot thông thường thành một AI Agent có khả năng thực hiện nghiệp vụ thực tế.

Luồng chính:

```text
Customer
   ↓
ChatMemory
   ↓
Customer ID
   ↓
getCustomerVouchers()
   ↓
LLM lựa chọn voucher
   ↓
applyVoucherToInvoice()
   ↓
Business Validation
   ↓
Database
   ↓
LLM tổng hợp
   ↓
Customer
```

Điểm cốt lõi của thiết kế là phân chia trách nhiệm rõ ràng:

```text
+----------------------+----------------------------------+
| Thành phần           | Trách nhiệm                      |
+----------------------+----------------------------------+
| ChatMemory            | Lưu context hội thoại            |
| LLM                   | Hiểu yêu cầu + điều phối Tool    |
| Tool 1                | Tra cứu voucher                  |
| Tool 2                | Thực hiện nghiệp vụ              |
| Service               | Business validation              |
| Repository            | Truy cập database                |
| Database              | Lưu trạng thái cuối cùng         |
+----------------------+----------------------------------+
```

Vì vậy, Agent vừa có khả năng tự động hóa workflow nhiều bước, vừa đảm bảo an toàn nghiệp vụ. Đặc biệt, backend vẫn là lớp kiểm soát cuối cùng đối với các thao tác làm thay đổi dữ liệu.

**Kết luận quan trọng nhất:**

> **AI có thể quyết định nên thử hành động nào, nhưng hệ thống backend mới là nơi quyết định hành động đó có được phép thực hiện hay không.**
