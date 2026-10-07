
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
            def dir = new File("/tmp/qp_project_shapes"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer("/home/user/LabConstrictor-Fiji/tests/fixtures/blobs.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("blobs.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "interactions", "Outline the blobs"); Thread.sleep(1500)
        def annotations = { fx { qupath.imageData.hierarchy.annotationObjects.findAll { it.name?.startsWith("interactions:outlines ") } } }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        println "STATUS " + status(dlg)
        def found = annotations()
        expect("one_annotation_per_blob", found.size() == 4, found*.name)
        expect("names_carry_the_label", found.any { it.name == "interactions:outlines 1" }, found*.name)
        expect("measurements_from_properties", found.every { it.getMeasurementList().getMeasurementNames().containsAll(["area", "label"]) }, found.collect { it.getMeasurementList().getMeasurementNames() })
        def ring = found.find { it.getMeasurementList().get("area") > 300 }
        expect("hole_is_kept", ring != null && ring.getROI().getArea() < 20 * 20 - 40, ring?.getROI()?.getArea())
        expect("annotations_are_on_the_blob", found.every { it.ROI.boundsX >= 0 && it.ROI.boundsX < 80 && it.ROI.boundsY >= 0 && it.ROI.boundsY < 60 }, found.collect { [it.ROI.boundsX, it.ROI.boundsY] })
        shot("qp_shapes_1_after", dlg.stage)
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("replace_leaves_one_set", annotations().size() == 4, annotations()*.name)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
