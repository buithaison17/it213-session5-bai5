package com.example.bai5.service;

import com.example.bai5.model.entity.Invoice;
import com.example.bai5.model.entity.Voucher;
import com.example.bai5.repository.InvoiceRepository;
import com.example.bai5.repository.VoucherRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VoucherService {
    private final VoucherRepository voucherRepository;
    private final InvoiceRepository invoiceRepository;

    public VoucherService(VoucherRepository voucherRepository, InvoiceRepository invoiceRepository) {
        this.voucherRepository = voucherRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Tool(description = "Lấy các mã giảm giá còn hiệu lực của người dùng theo mã khách hàng")
    public List<Voucher> getCustomerVouchers(
            @ToolParam(description = "Mã khách hàng, ví dụ: KH888")
            String customerId
    ) {
        return voucherRepository.findByCustomerIdAndIsExpired(customerId, false);
    }

    @Tool(description = "Áp dụng mã giảm giá tốt nhất vào hóa đơn đặt phòng trong hệ thống và cập nhật cơ sở dữ liệu")
    @Transactional
    public Invoice applyVoucherToInvoice(
            @ToolParam(description = "Mã hóa đơn đặt phòng cần áp dụng, ví dụ: 999 hoặc HD999 tùy cấu hình ID")
            Long invoiceId,
            @ToolParam(description = "Mã giảm giá được chọn, ví dụ: VIP20")
            String voucherCode
    ) {
        // 1. Kiểm tra tính hợp lệ tham số đầu vào
        if (invoiceId == null || voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Mã hóa đơn hoặc mã voucher không được để trống.");
        }

        // 2. Tìm kiếm hóa đơn trong MySQL Database
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Hóa đơn với mã " + invoiceId + " không tồn tại."));

        // 3. Lập trình phòng thủ: Kiểm tra trạng thái hóa đơn đã thanh toán hoặc bị hủy
        if ("PAID".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalArgumentException("Hóa đơn này đã được thanh toán trước đó, không thể áp dụng voucher.");
        }
        if ("CANCELED".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalArgumentException("Hóa đơn này đã bị hủy, không thể áp dụng voucher.");
        }

        // 4. Kiểm tra xem voucher có tồn tại và còn hạn không
        Voucher voucher = voucherRepository.findByCode(voucherCode)
                .orElseThrow(() -> new IllegalArgumentException("Mã giảm giá không tồn tại."));

        if (Boolean.TRUE.equals(voucher.getIsExpired())) {
            throw new IllegalArgumentException("Mã giảm giá này đã hết hạn sử dụng.");
        }

        // 5. Kiểm tra tính bảo mật nghiệp vụ: Voucher có thuộc về đúng khách hàng của hóa đơn không
        if (invoice.getCustomerId() != null && !invoice.getCustomerId().equals(voucher.getCustomerId())) {
            throw new IllegalArgumentException("Mã giảm giá này không thuộc về khách hàng sở hữu hóa đơn này.");
        }

        // 6. Tính toán giảm giá thực tế (Discount theo phần trăm)
        double originalAmount = invoice.getUnitPrice();
        double discountPercent = voucher.getDiscount() != null ? voucher.getDiscount() : 0.0;
        double finalAmount = originalAmount * (1.0 - (discountPercent / 100.0));

        // Đảm bảo số tiền sau giảm không âm
        if (finalAmount < 0) {
            finalAmount = 0.0;
        }

        // 7. Cập nhật trực tiếp xuống MySQL Database (chỉ gọi save 1 lần duy nhất)
        invoice.setFinalPrice(finalAmount);
        invoice.setStatus("DISCOUNTED");
        invoice.setAppliedVoucher(voucherCode);

        return invoiceRepository.save(invoice);
    }
}