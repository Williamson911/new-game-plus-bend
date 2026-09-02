package be.technifutur.newgameplus.mailer;

import org.thymeleaf.context.Context;

import java.util.List;

public class MailerThread implements Runnable {

    private final MailerUtils mailerUtils;
    private final String subject;
    private final List<String> to;
    private final String templateName;
    private final Context context;

    public MailerThread(MailerUtils mailerUtils,
                         String subject,
                         String templateName,
                         Context context,
                         String... to) {
        this.mailerUtils = mailerUtils;
        this.subject = subject;
        this.templateName = templateName;
        this.context = context;
        this.to = List.of(to);
    }

    @Override
    public void run() {
        mailerUtils.sendMail(
                this.subject,
                this.templateName,
                this.context,
                this.to.toArray(new String[0])
        );
    }
}
