package org.cellmigrationlab.labconstrictor.qupath;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.MenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Menu entry "Extensions > LabConstrictor tools..." that runs the bundled Groovy script (the script is the implementation; this jar
 * is the packaging, as in LabConstrictor-Fiji). System property lc.qupath.test.script (tests only): a Groovy file to run once
 * QuPath has started.
 */
public class LabConstrictorExtension implements QuPathExtension {
    private static final String FOLDER = "/org/cellmigrationlab/labconstrictor/qupath/";
    /** The script files in the order they are joined into one Groovy script (the file names are the lines of parts.txt; the first is the main script). */
    private static final String PARTS = FOLDER + "parts.txt";

    @Override
    public void installExtension(QuPathGUI qupath) {
        MenuItem item = new MenuItem("LabConstrictor tools...");
        item.setOnAction(e -> run(qupath, null));
        qupath.getMenu("Extensions", true).getItems().add(item);
        String test = System.getProperty("lc.qupath.test.script");
        if (test != null && !test.isEmpty()) {
            new Thread(() -> {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    // the test hook is only a delay before opening the dialog: restore the flag and do not run the script
                    Thread.currentThread().interrupt();
                    System.err.println("LabConstrictor: test hook interrupted, the test script is not run");
                    return;
                }
                Platform.runLater(() -> run(qupath, test));
            }, "lc-test-hook").start();
        }
    }

    private void run(QuPathGUI qupath, String testFile) {
        try {
            String text;
            if (testFile != null) {
                text = new String(Files.readAllBytes(Paths.get(testFile)), StandardCharsets.UTF_8);
            } else {
                text = joinedScript();
            }
            Binding binding = new Binding();
            binding.setVariable("qupath", qupath);
            new GroovyShell(getClass().getClassLoader(), binding).evaluate(text);
        } catch (Throwable t) {
            t.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR, "LabConstrictor tools could not start: " + t);
            alert.show();
        }
    }

    /** The text of every script file listed in parts.txt, one after the other, as one script (what tests/run_gui_test.sh and build.sh also do). */
    private String joinedScript() throws java.io.IOException {
        StringBuilder all = new StringBuilder();
        for (String line : resourceText(PARTS).split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                all.append(resourceText(FOLDER + name)).append('\n');
            }
        }
        return all.toString();
    }

    private String resourceText(String path) throws java.io.IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new java.io.IOException("the resource " + path + " is missing from the extension jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String getName() {
        return "LabConstrictor tools";
    }

    @Override
    public String getDescription() {
        return "Run the tools of the installed LabConstrictor apps (NucleiSky, CellTracksColab, ...) from QuPath.";
    }
}
