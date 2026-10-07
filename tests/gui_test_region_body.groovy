// ================================================================== GUI test body: RegionOf (appended to the tool script by tests/run_gui_test.sh)
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
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
    fx { ImageIO.write(SwingFXUtils.fromFXImage(st.scene.snapshot(null), null), "png", new File(OUT, name + ".png")) }
    println "SHOT " + name
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }

Thread.start("lc-gui-test") {
    try {
        fx {
            def dir = new File("/tmp/qp_project_region"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer("/home/user/LabConstrictor-Fiji/tests/fixtures/blobs.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("blobs.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(1500)
        def spotCount = { dlg2 -> def m = (status(dlg2) =~ /(\d+)/); fx { dlg2.messageLabel.text } }
        def box = fx { dlg.selectionBoxes["region"] }
        expect("use_the_selection_box_exists", box != null && !fx { box.selected }, box)
        def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
        def hierarchy = fx { qupath.imageData.hierarchy }
        def top = qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(0, 0, 25, 25, plane))
        def middle = qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(28, 28, 25, 25, plane))
        def far = qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(60, 0, 10, 15, plane))
        fx { hierarchy.addObjects([top, middle, far]) }

        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        def whole = fx { dlg.messageLabel.text }
        expect("without_the_selection_the_whole_image_is_searched", whole.contains("Found 492"), whole)

        fx { hierarchy.selectionModel.setSelectedObject(top) }
        fx { box.selected = true }
        expect("chooser_yields_to_the_selection", fx { dlg.controls["region"].disable }, "")
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        def one = fx { dlg.messageLabel.text }
        expect("one_selected_annotation_limits_the_search", one.contains("Found 100"), one)

        fx { hierarchy.selectionModel.setSelectedObjects([top, middle], null) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        def two = fx { dlg.messageLabel.text }
        expect("two_selected_annotations_are_labels_1_and_2", two.contains("Found 436"), two)

        fx { hierarchy.selectionModel.clearSelection() }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("no_selection_is_explained", status(dlg).contains("no annotation is selected") || fx { dlg.messageLabel.text }.contains("no annotation is selected"), status(dlg))

        def line = fx { dlg.copyAsCommand("terminal") }
        expect("copy_notes_the_selection", line.contains("the selection cannot be copied") && line.contains("region=region.tif"), line)
        shot("qp_region_1_form", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
