package com.example.bai5.controller;

import com.example.bai5.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;
    private final VoucherService voucherService;

    @GetMapping
    public String chat(
            @RequestParam String conversationId,
            @RequestParam String message
    ) {
        return chatClient
                .prompt()
                .user(message)
                .system("""
                        Bạn là trợ lý chăm sóc khách hàng chuyên nghiệp của R-Hotels
                        Nhiệm vụ của bạn là kiểm tra xem khách hàng đã cung cấp mã khách hàng
                        Nếu chưa có, hãy chủ động hỏi khách hàng
                        Khi đã có thông tin, hãy gọi công cụ để lấy danh sách voucher, chọn ra mã tốt nhất và áp dụng vào hóa đơn
                        """)
                .tools(voucherService)
                .advisors(advisorSpec -> advisorSpec.param(
                        ChatMemory.CONVERSATION_ID, conversationId
                ))
                .call()
                .content();
    }
}
