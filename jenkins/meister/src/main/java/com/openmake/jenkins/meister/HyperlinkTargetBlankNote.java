package com.openmake.jenkins.meister;

import hudson.Extension;
import hudson.MarkupText;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

public class HyperlinkTargetBlankNote extends ConsoleNote {
 /**
  * If this starts with '/', it's interpreted as a path within the context path.
  */
 private final String url;
 private final int length;

 public HyperlinkTargetBlankNote(String url, int length) {
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
     text.addMarkup(charPos, charPos + length, "<br><a href='" + url + "'"+extraAttributes()+">", "</a><br>");
     return null;
 }

 protected String extraAttributes() {
     return "target=_blank";
 }

 public static String encodeTo(String url, String text) {
     try {
         return new HyperlinkTargetBlankNote(url,text.length()).encode()+text;
     } catch (IOException e) {
         // impossible, but don't make this a fatal problem
         LOGGER.log(Level.WARNING, "Failed to serialize "+HyperlinkTargetBlankNote.class,e);
         return text;
     }
 }

 @Extension
 public static class DescriptorImpl extends ConsoleAnnotationDescriptor {
     public String getDisplayName() {
         return "HyperlinksTargeBlank";
     }
 }

 private static final Logger LOGGER = Logger.getLogger(HyperlinkTargetBlankNote.class.getName());
}
