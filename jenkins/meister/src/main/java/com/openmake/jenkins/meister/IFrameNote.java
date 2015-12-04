package com.openmake.jenkins.meister;

import hudson.Extension;
import hudson.MarkupText;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import hudson.console.ModelHyperlinkNote;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Turns a text into a hyperlink by specifying the URL separately.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.362
 * @see ModelHyperlinkNote
 */
public class IFrameNote extends ConsoleNote {
    /**
     * If this starts with '/', it's interpreted as a path within the context path.
     */
    private final String url;
    private final int length;

    public IFrameNote(String url, int length) {
        this.url = url;
        this.length = length;
    }

    @Override
    public ConsoleAnnotator annotate(Object context, MarkupText text, int charPos) {
        String url = this.url;
        if (url.startsWith("/")) {
            StaplerRequest req = Stapler.getCurrentRequest();
            if (req!=null) {
                // if we are serving HTTP request, we want to use app relative URL
                url = req.getContextPath()+url;
            } else {
                // otherwise presumably this is rendered for e-mails and other non-HTTP stuff
                url = Jenkins.getInstance().getRootUrl()+url.substring(1);
            }
        }
        text.addMarkup(charPos, charPos + length, "<iframe src='" + url + "'"+extraAttributes()+" frameborder=\"0\" height=\"1200px\" width=\"100%\">", "</iframe>");
        return null;
    }

    protected String extraAttributes() {
        return "";
    }

    public static String encodeTo(String url, String text) {
        try {
            return new IFrameNote(url,text.length()).encode()+text;
        } catch (IOException e) {
            // impossible, but don't make this a fatal problem
            LOGGER.log(Level.WARNING, "Failed to serialize "+IFrameNote.class,e);
            return text;
        }
    }

    @Extension
    public static class DescriptorImpl extends ConsoleAnnotationDescriptor {
        public String getDisplayName() {
            return "IFrames";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(IFrameNote.class.getName());
}
