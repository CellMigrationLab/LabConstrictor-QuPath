// ================================================================== GUI test body (appended to the tool script by tests/run_gui_test.sh)
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FX = "/home/tester/q/fx"
def results = [:]
def fx = { Closure c ->
    if (Platform.isFxApplicationThread()) return c()
    def latch = new CountDownLatch(1); def box = [null, null]
    Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
def shot = { String name, Stage st ->
    Thread.sleep(600)
    fx {
        def img = st.scene.snapshot(null)
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(OUT, name + ".png"))
    }
    println "SHOT " + name
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def resultStages = { Window.getWindows().findAll { it instanceof Stage && it.showing && it.title?.contains(": ") && it.title != "LabConstrictor: last run" } }


Thread.start("lc-gui-test") {
    try {
        fx {
            def dir = new File("/tmp/qp_project_fallbacks"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer("/home/user/LabConstrictor-Fiji/tests/fixtures/sample.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("sample.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def NOT_AN_IMAGE = "/tmp/qp_fallbacks_not_an_image.tif"
        new File(NOT_AN_IMAGE).text = "this is text, not a TIFF"
        def logged = { String needle -> LcLog.recent.any { it.contains(needle) } }
        def textFieldOf = { String name -> fx { dlg.wrappers[name].lookupAll(".text-field").find { it instanceof TextField && !(it.parent instanceof Spinner) } } }

        // a number typed into a box that is not a number: the old value stays, and the person is told (status line + log)
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(1500)
        def spinner = fx { dlg.controls["threshold"] }
        def before = fx { spinner.value }
        fx { spinner.editor.text = "abc"; dlg.commitSpinner(spinner, "Threshold") }
        expect("fallbacks_bad_number_is_reported", status(dlg).contains("not a number") && status(dlg).contains("Threshold"), status(dlg))
        expect("fallbacks_bad_number_keeps_old_value", fx { spinner.value } == before, fx { spinner.value })
        expect("fallbacks_bad_number_is_logged", logged("'abc' is not a number"), LcLog.recent)

        // copy as command: a value that cannot be read is named in the command's notes instead of vanishing
        def realGetter = fx { dlg.getters["threshold"] }
        fx { dlg.getters["threshold"] = { throw new IllegalStateException("boom") } }
        def line = fx { dlg.copyAsCommand("terminal") }
        fx { dlg.getters["threshold"] = realGetter }
        expect("fallbacks_unreadable_value_is_noted_in_the_command", line != null && line.contains("# threshold: the value could not be read (boom)") && !line.contains("threshold="), line)
        expect("fallbacks_unreadable_value_is_logged", logged("could not read 'threshold'"), LcLog.recent)

        // points of an image that is not the open one: table only, said once in the log
        fx { dlg.imageBoxes["image"].value = LcDialog.FILE_CHOICE }
        fx { textFieldOf("image").text = "/home/user/LabConstrictor-Fiji/tests/fixtures/sample.tif" }
        fx { dlg.setters["threshold"](0.5d) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("fallbacks_points_not_placed_is_logged", logged("not placed: they were not found in the image open in QuPath"), LcLog.recent)

        // a run that fails on the host (the file does not exist): the stack trace is in Details, the status line says why
        fx { textFieldOf("image").text = "/tmp/qp_fallbacks_missing_file.tif" }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("fallbacks_host_failure_shows_in_status", status(dlg).startsWith("failed: "), status(dlg))
        expect("fallbacks_host_failure_has_trace_in_details", fx { dlg.lastReport.contains("\"trace\"") && dlg.lastReport.contains("\\tat ") }, fx { dlg.lastReport.take(400) })
        expect("fallbacks_host_failure_is_logged", logged("failed on the host"), LcLog.recent)

        // the channel list of a file QuPath cannot read: reported, the form keeps working
        pick(dlg, "interactions", "Mean of a channel"); Thread.sleep(1500)
        fx { dlg.imageBoxes["image"].value = LcDialog.FILE_CHOICE }
        fx { textFieldOf("image").text = NOT_AN_IMAGE }
        fx { dlg.imageBoxes["image"].value = dlg.imageBoxes["image"].items.find { it != LcDialog.FILE_CHOICE } ?: LcDialog.NO_IMAGE }
        fx { dlg.imageBoxes["image"].value = LcDialog.FILE_CHOICE }
        Thread.sleep(800)
        expect("fallbacks_unreadable_channel_file_is_reported", status(dlg).contains("Channels of") && status(dlg).contains("could not be read"), status(dlg))
        expect("fallbacks_unreadable_channel_file_keeps_form", fx { !dlg.channelBoxes["image"].visible && !dlg.runButton.disable })
        expect("fallbacks_unreadable_channel_file_is_logged", logged("channels of 'image' could not be read"), LcLog.recent)
        expect("fallbacks_single_channel_chooser_hidden_is_logged", logged("no channel chooser for 'image'"), LcLog.recent)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
