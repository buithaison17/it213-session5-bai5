package com.example.bai5.config;

import com.example.bai5.model.entity.Invoice;
import com.example.bai5.model.entity.Voucher;
import com.example.bai5.repository.InvoiceRepository;
import com.example.bai5.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final VoucherRepository voucherRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    public void run(String... args) throws Exception {
        if (voucherRepository.count() == 0) {
            Voucher v1 = new Voucher();
            v1.setCode("VIP20");
            v1.setCustomerId("KH888");
            v1.setDiscount(20.0); // Giảm 20%
            v1.setIsExpired(false);
            voucherRepository.save(v1);

            Voucher v2 = new Voucher();
            v2.setCode("WELCOME10");
            v2.setCustomerId("KH888");
            v2.setDiscount(10.0); // Giảm 10%
            v2.setIsExpired(false);
            voucherRepository.save(v2);

            System.out.println(">>> Đã khởi tạo dữ liệu mẫu Vouchers thành công!");
        }

        if (invoiceRepository.count() == 0) {
            Invoice inv1 = new Invoice();
            inv1.setCustomerId("KH888");
            inv1.setUnitPrice(2500000.0);
            inv1.setFinalPrice(2500000.0);
            inv1.setStatus("UNPAID");
            invoiceRepository.save(inv1);

            Invoice inv2 = new Invoice();
            inv2.setCustomerId("KH888");
            inv2.setUnitPrice(1500000.0);
            inv2.setFinalPrice(1500000.0);
            inv2.setStatus("PAID"); // Hóa đơn đã thanh toán để test phòng thủ
            invoiceRepository.save(inv2);
            System.out.println(">>> Đã khởi tạo dữ liệu mẫu Invoices thành công!");
        }
    }
}
