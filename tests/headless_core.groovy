def reg = LcRegistry.load()
println "APPS " + reg.apps.keySet() + " PROBLEMS " + reg.problems
def D = "/home/tester/humantest"
def run = { String app, String tool, Map inputs ->
    def w = new LcWorker(reg.apps[app].entry)
    def updates = []
    def out = w.run(tool, inputs) { m, c, mx -> updates << m }
    w.close()
    println "RESULT " + app + "/" + tool + " -> " + out.responseType + " updates=" + updates.size() + " " + (out.error ?: "") + " " + LcJson.toJson(out.outputs?.results?.collect { [it.type, it.name, it.values ?: it.path] })
}
run("CellTracksColab", "calculate_metrics", [tracks: D + "/ct/tracks.csv"])
run("NucleiSky", "relocalize", [reference: D + "/ns/reference.tif", query: D + "/ns/query.tif", reference_pixel_size_um: 0.65, query_pixel_size_um: 0.325])
run("NucleiSky", "relocalize", [reference: D + "/ns/reference.tif", query: D + "/ns/query.tif", matcher: "triangles"])
run("NucleiSky", "relocalize", [reference: "/nonexistent.tif", query: D + "/ns/query.tif"])
