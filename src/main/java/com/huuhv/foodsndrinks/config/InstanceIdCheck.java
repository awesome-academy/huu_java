package com.huuhv.foodsndrinks.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Profile prod: chặn khởi động khi chưa khai báo {@code INSTANCE_ID} ({@code app.instance-id}) hoặc giá trị không hợp lệ.
 * <p>
 * Ở prod {@code app.instance-id} là tên thư mục log {@code logs/<instance-id>/}, nên phải là tên cố định cho từng
 * instance. Không dùng hostname làm mặc định: container Docker tạo lại (mỗi lần deploy) nhận hostname mới → log ghi
 * sang thư mục mới, còn thư mục cũ không bao giờ được dọn ({@code maxHistory}/{@code totalSizeCap} của Logback chỉ dọn
 * file trong thư mục của appender đang chạy), giới hạn "30 ngày, 1GB / instance" không còn đúng.
 * <p>
 * Chỉ nhận chữ, số, {@code .}, {@code _}, {@code -} (tối đa 64 ký tự, không bắt đầu bằng {@code .}): giá trị được ghép
 * vào đường dẫn thư mục log và in trong mỗi dòng log, nên chặn {@code ../}, {@code /} và ký tự ngắt dòng.
 * <p>
 * Logback được khởi tạo trước khi check này chạy, nên khi thiếu biến vài dòng log lúc khởi động (kể cả lỗi khởi động)
 * được ghi vào {@code logs/} thay vì thư mục của instance. Là một {@link WebServerFactoryCustomizer} để được tạo sớm,
 * trước khi web server khởi động (xem {@link TrustedProxiesCheck}).
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdCheck implements WebServerFactoryCustomizer<WebServerFactory> {

    static final String PROPERTY = "app.instance-id";
    private static final Pattern VALID_INSTANCE_ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

    public InstanceIdCheck(Environment environment) {
        String instanceId = Binder.get(environment).bind(PROPERTY, String.class).orElse("");
        if (instanceId.isBlank() || instanceId.contains("${")) {
            throw new IllegalStateException("INSTANCE_ID chưa được khai báo hoặc đang rỗng (profile prod): đặt tên cố định, "
                    + "khác nhau cho từng instance (vd. app-1) — giữ nguyên qua các lần deploy để log ghi tiếp vào cùng "
                    + "thư mục logs/<INSTANCE_ID>/");
        }
        // Không đưa giá trị vào message: có thể chứa ký tự ngắt dòng
        if (!VALID_INSTANCE_ID.matcher(instanceId).matches()) {
            throw new IllegalStateException("INSTANCE_ID không hợp lệ (profile prod): chỉ gồm chữ, số, '.', '_', '-', "
                    + "tối đa 64 ký tự, không bắt đầu bằng '.'");
        }
    }

    /** Không cấu hình gì — kiểm tra đã chạy trong constructor (xem javadoc của class). */
    @Override
    public void customize(WebServerFactory factory) {
        // no-op
    }
}
