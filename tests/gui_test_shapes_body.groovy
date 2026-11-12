
// ================================================================== GUI test body (appended to the tool script by tests/run_gui_test.sh)
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)   // the sample images in this repo (the runner passes the absolute path)
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
            def server = qupath.lib.images.servers.ImageServers.buildServer(FIXTURES + "/blobs.tif")
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
        expect("one_classification_per_output", found.every { it.pathClass?.toString() == qupath.lib.objects.classes.PathClass.fromString("interactions:outlines").toString() }, found*.pathClass)
        expect("measurements_from_properties", found.every { it.getMeasurementList().getMeasurementNames().containsAll(["area", "label"]) }, found.collect { it.getMeasurementList().getMeasurementNames() })
        def ring = found.find { it.getMeasurementList().get("area") > 300 }
        expect("hole_is_kept", ring != null && ring.getROI().getArea() < 20 * 20 - 40, ring?.getROI()?.getArea())
        expect("annotations_are_on_the_blob", found.every { it.ROI.boundsX >= 0 && it.ROI.boundsX < 80 && it.ROI.boundsY >= 0 && it.ROI.boundsY < 60 }, found.collect { [it.ROI.boundsX, it.ROI.boundsY] })
        shot("qp_shapes_1_after", dlg.stage)
        expect("results_carry_their_output", found.every { it.metadata["labconstrictor.output"] == "outlines" && it.metadata["labconstrictor.app"] == "interactions" }, found*.metadata)
        // Ownership (audit Q1, Q3): an annotation of the person's own with the same class survives Replace; a result the person reclassified is still replaced.
        def outlinesClass = qupath.lib.objects.classes.PathClass.fromString("interactions:outlines")
        def mine = fx {
            def a = PathObjects.createAnnotationObject(ROIs.createRectangleROI(1, 1, 5, 5, ImagePlane.getDefaultPlane()))
            a.pathClass = outlinesClass; a.name = "my own"; qupath.imageData.hierarchy.addObject(a); a
        }
        def reclassified = found[0]
        fx { reclassified.pathClass = qupath.lib.objects.classes.PathClass.fromString("Tumor"); reclassified.name = null }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        def all = fx { new ArrayList(qupath.imageData.hierarchy.annotationObjects) }
        expect("replace_leaves_one_set", annotations().size() == 4, annotations()*.name)
        expect("replace_keeps_the_persons_annotation_of_the_same_class", all.contains(mine), all*.name)
        expect("replace_removes_a_reclassified_result", !all.contains(reclassified), all.collect { [it.name, it.pathClass] })
        // Above 50 outlines (audit Q2): no names, no invented "label"; the position is outline_index; a text label is kept.
        def square = { int i -> [type: "Feature", geometry: [type: "Polygon", coordinates: [[[i, 0], [i + 1, 0], [i + 1, 1], [i, 1], [i, 0]]]], properties: [:]] }
        def built50 = fx { dlg.shapeAnnotations([features: (0..<50).collect { square(it * 2) }], "x:y") }
        def features51 = (0..<51).collect { square(it * 2) }
        features51[0].put("properties", [label: "cell A"])
        features51[1].put("properties", [label: 7.0d])
        def built51 = fx { dlg.shapeAnnotations([features: features51], "x:y") }
        expect("exactly_50_outlines_are_named", built50.objects.every { it.name != null }, built50.objects*.name.take(3))
        expect("51_outlines_are_not_named", built51.objects.every { it.name == null }, built51.objects*.name.take(3))
        expect("a_text_label_is_kept_as_metadata", built51.objects[0].metadata["label"] == "cell A" && !built51.objects[0].measurementList.containsKey("label"), built51.objects[0].metadata)
        expect("a_numeric_label_stays_the_label_measurement", built51.objects[1].measurementList.get("label") == 7.0d && !built51.objects[1].measurementList.containsKey("outline_index"), built51.objects[1].measurementList.getMeasurementNames())
        expect("no_label_gives_outline_index_not_label", built51.objects[2].measurementList.get("outline_index") == 3.0d && !built51.objects[2].measurementList.containsKey("label"), built51.objects[2].measurementList.getMeasurementNames())
    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
