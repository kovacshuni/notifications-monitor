package com.ft.notificationsmonitor;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.http.javadsl.Http;
import com.ft.notificationsmonitor.model.HttpConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class NotificationsMonitor {

    private static Logger logger = LoggerFactory.getLogger(NotificationsMonitor.class);

    private ActorSystem sys;
    private ActorRef pushConnector;
    private Cancellable pullSchedule;
    private Cancellable longPullSchedule;
    private Cancellable pushPullMatcherReport;
    private Cancellable pullPullMatcherReport;

    public static void main(String[] args) throws Exception {
        NotificationsMonitor monitor = new NotificationsMonitor();
        monitor.run();
        scala.sys.ShutdownHookThread.apply(monitor::shutdown);
    }

    private void run() {
        sys = ActorSystem.create("notifications-monitor");

        logger.info("Starting up...");

        Config sensitiveConfig = ConfigFactory.parseFile(new File("src/main/resources/.sensitive.conf"));
        Config config = ConfigFactory.load().withFallback(sensitiveConfig);
        String username = sensitiveConfig.getString("basic-auth.username");
        String password = sensitiveConfig.getString("basic-auth.password");
        ActorRef pushPullMatcher = sys.actorOf(PairMatcher.props("push", "pull"), "matcher-push-pull");
        ActorRef pullPullMatcher = sys.actorOf(PairMatcher.props("pull", "long-pull"), "matcher-pull-pull");
        HttpConfig pushHttpConfig = new HttpConfig(config.getString("push-host"), config.getInt("push-port"),
                config.getString("push-uri"), username, password);
        pushConnector = sys.actorOf(PushConnector.props(pushHttpConfig, pushPullMatcher), "push-connector");
        HttpConfig pullHttpConfig = new HttpConfig(config.getString("pull-host"), config.getInt("pull-port"),
                config.getString("pull-uri"), username, password);
        ActorRef pullConnector = sys.actorOf(PullConnector.props(pullHttpConfig, Arrays.asList(pushPullMatcher, pullPullMatcher)), "pull-connector");
        ActorRef longPullConnector = sys.actorOf(PullConnector.props(pullHttpConfig, Collections.singletonList(pullPullMatcher)), "long-pull-connector");
        pullSchedule = sys.scheduler().schedule(Duration.apply(0, TimeUnit.SECONDS) ,
                Duration.apply(5, TimeUnit.SECONDS), pullConnector, "RequestSinceLast", sys.dispatcher(), ActorRef.noSender());
        longPullSchedule = sys.scheduler().schedule(Duration.apply(0, TimeUnit.SECONDS) ,
                Duration.apply(10, TimeUnit.SECONDS), longPullConnector, "RequestSinceLast", sys.dispatcher(), ActorRef.noSender());
        pushPullMatcherReport = sys.scheduler().schedule(Duration.apply(3, TimeUnit.MINUTES),
                Duration.apply(3, TimeUnit.MINUTES), pushPullMatcher, "Report", sys.dispatcher(), ActorRef.noSender());
        pullPullMatcherReport = sys.scheduler().schedule(Duration.apply(0, TimeUnit.SECONDS),
                Duration.apply(3, TimeUnit.MINUTES), pullPullMatcher, "Report", sys.dispatcher(), ActorRef.noSender());
        pushConnector.tell("Connect", ActorRef.noSender());
    }

    private BoxedUnit shutdown() {
        logger.info("Exiting...");
        pullSchedule.cancel();
        longPullSchedule.cancel();
        pushPullMatcherReport.cancel();
        pullPullMatcherReport.cancel();
        pushConnector.tell("CancelStreams", ActorRef.noSender());
        Http.get(sys).shutdownAllConnectionPools()
                .whenComplete((s, f) -> sys.terminate());
        return BoxedUnit.UNIT;
    }
}