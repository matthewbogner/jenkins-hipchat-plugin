package jenkins.plugins.hipchat;

import hudson.Util;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import org.apache.commons.lang.StringUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(HipChatListener.class.getName());

    HipChatNotifier notifier;

    public ActiveNotifier(HipChatNotifier notifier) {
        super();
        this.notifier = notifier;
    }

    private HipChatService getHipChat(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(HipChatNotifier.HipChatJobProperty.class).getRoom());
        return notifier.newHipChatService(projectRoom);
    }

    public void deleted(AbstractBuild r) {
    }

    public void started(AbstractBuild build) {
        String changes = getChanges(build);
        CauseAction cause = build.getAction(CauseAction.class);

        if (changes != null) {
            notifyStart(build, changes);
        } else if (cause != null) {
            MessageBuilder message = new MessageBuilder(notifier, build);
            message.append(cause.getShortDescription());
            notifyStart(build, message.appendOpenLink().toString());
        } else {
            notifyStart(build, getBuildStatusMessage(build));
        }
    }

    private void notifyStart(AbstractBuild build, String message) {
        getHipChat(build).publish(message, "green");
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        logger.info("HipChat notifier processing build...");
        AbstractProject<?, ?> project = r.getProject();
        HipChatNotifier.HipChatJobProperty jobProperty = project.getProperty(HipChatNotifier.HipChatJobProperty.class);
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousBuild();
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
        if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                || (result == Result.FAILURE && jobProperty.getNotifyFailure())
                || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                || (result == Result.SUCCESS && previousResult == Result.FAILURE && jobProperty.getNotifyBackToNormal())
                || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {

            String msg = getBuildStatusMessage(r);
            String color = getBuildColor(r);

            logger.info("Publishing to hipchat... color: " + color + ", msg: " + msg);
            getHipChat(r).publish(msg, color, "html");
        }
    }

    String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        return message.appendOpenLink().toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "green";
        } else if (result == Result.FAILURE || result == Result.UNSTABLE) {
            return "red";
        } else {
            return "yellow";
        }
    }

    String getBuildStatusMessage(AbstractBuild r) {
        MessageBuilder message = new MessageBuilder(notifier, r);

        return message.appendAtAllMention()
                      .appendStatusMessage()
                      .appendDuration()
                      .appendOpenLink()
                      .appendCulprits()
                      //.appendChanges()
                      .toString();
    }

    public static class MessageBuilder {
        private StringBuffer message;
        private HipChatNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(HipChatNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            appendGraphic();
            startMessage();
        }

        public MessageBuilder appendAtAllMention() {
            Result result = build.getResult();
            AbstractProject<?, ?> project = build.getProject();
            HipChatNotifier.HipChatJobProperty jobProperty = project.getProperty(HipChatNotifier.HipChatJobProperty.class);
            if ((result == Result.FAILURE || result == Result.UNSTABLE) && jobProperty.getMentionAll()) {
                message.append("@all ");
            }

            return this;
        }

        public MessageBuilder appendStatusMessage() {
            message.append(getStatusMessage(build));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) return "Back to normal";
            if (result == Result.SUCCESS) return "successful";
            if (result == Result.FAILURE) return "failed";
            if (result == Result.ABORTED) return "aborted";
            if (result == Result.NOT_BUILT) return "Not built";
            if (result == Result.UNSTABLE) return "unstable";
            return "Unknown";
        }

        public MessageBuilder append(String string) {
            message.append(string);
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(string.toString());
            return this;
        }

        private MessageBuilder startMessage() {
            message.append(build.getProject().getDisplayName());
            message.append(" - ");
            message.append(build.getDisplayName());
            message.append(" ");
            return this;
        }

        public MessageBuilder appendOpenLink() {
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<a href='").append(url).append("'>Open</a>)");
            return this;
        }

        public MessageBuilder appendGraphic() {
            String baseUrl = notifier.getBuildServerUrl();
            String successImg = baseUrl + "static/0ccb0342/images/24x24/blue.png";
            String failImg = baseUrl + "static/0ccb0342/images/24x24/red.png";
            String img = successImg;

            Result result = build.getResult();
            if (result == Result.UNSTABLE
                    || result == Result.FAILURE
                    || result == Result.ABORTED) {
                img = failImg;
            }
            message.append("<img src='").append(img).append("' alt='Failed'/> ");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            message.append(build.getDurationString());
            return this;
        }

        public MessageBuilder appendCulprits() {
            logger.info("Appending culprits...");
            Result result = build.getResult();
            if (result != Result.UNSTABLE
                    && result != Result.FAILURE) {
                // No need to append culprits
                return this;
            }

            Set<User> culprits = build.getCulprits();
            StringBuilder culpritBuilder = new StringBuilder();
            if (culprits != null && culprits.size() > 0) {
                for (User culprit : culprits) {
                    culpritBuilder.append("<li>").append(culprit.getDisplayName()).append("</li>");
                }

                message.append("<br /><span style='margin-left:20px'><b>Culprits:</b></span><ol>").append(culpritBuilder.toString()).append("</ol>");
            }
            return this;
        }

        public MessageBuilder appendChanges() {
            Result result = build.getResult();
            if (result != Result.UNSTABLE
                    && result != Result.FAILURE) {
                // No need to append changes
                return this;
            }

            if (!build.hasChangeSetComputed()) {
                logger.info("No change set computed...");
                return this;
            }
            ChangeLogSet changeSet = build.getChangeSet();
            Set<AffectedFile> files = new HashSet<AffectedFile>();
            for (Object o : changeSet.getItems()) {
                Entry entry = (Entry) o;
                files.addAll(entry.getAffectedFiles());
            }
            if (files.isEmpty()) {
                logger.info("Empty changes...");
                return this;
            }

            int fileCtr = 0;
            int maxFiles = 10;
            StringBuilder filesBuilder = new StringBuilder();
            for (AffectedFile file : files) {
                filesBuilder.append("<li>").append(file.getPath()).append("</li>");
                if (++fileCtr >= maxFiles) {
                    filesBuilder.append("<li>... and ").append(files.size() - maxFiles).append(" more files</li>");
                    break;
                }
            }

            message.append("<br /><span style='margin-left:20px'><b>Changes:</b></span><ol>").append(filesBuilder.toString()).append("</ol>");
            return this;
        }

        public String toString() {
            return message.toString();
        }
    }
}
