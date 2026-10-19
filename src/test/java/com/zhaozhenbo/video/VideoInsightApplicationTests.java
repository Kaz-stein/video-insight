package com.zhaozhenbo.video;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文冒烟测试。
 *
 * @ActiveProfiles("test") 让这个测试用 H2（见 application-test.yml），
 * 而不是去连本机 MySQL——否则"没密码就跑不了测试"，CI 也没法跑。
 * 注意：这只解决"测试能跑"，不代表生产能用 H2。
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoInsightApplicationTests {

	@Test
	void contextLoads() {
	}

}
