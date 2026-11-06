package dataguard;

import dataguard.cli.InspectionCli;
import dataguard.core.QualityEngine;
import dataguard.core.ReportRenderer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 正式 V1 入口；原始 Day01 TODO 独立保存在 learning/day01，未计作学生完成。 */
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        if (args.length > 0 && "inspect".equals(args[0])) {
            int status = new InspectionCli().run(args, System.out, System.err);
            if (status != 0) System.exit(status);
            return;
        }
        SpringApplication.run(Main.class, args);
    }

    @Bean
    QualityEngine qualityEngine() {
        return new QualityEngine();
    }

    @Bean
    ReportRenderer reportRenderer() {
        return new ReportRenderer();
    }
}
