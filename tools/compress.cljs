#!/usr/bin/env -S npx nbb
(ns compress
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    ["@gltf-transform/core" :refer [NodeIO]]
    ["@gltf-transform/extensions" :refer [ALL_EXTENSIONS]]
    ["@gltf-transform/functions" :as gf]
    ["meshoptimizer" :refer [MeshoptSimplifier]]
    ["sharp$default" :as sharp]
    ["draco3d$default" :as draco3d]
    ["glob" :refer [glob]]
    ["fs" :as fs]
    ["path" :as path]
    [promesa.core :as p]
    [clojure.tools.cli :as cli]
    [nbb.core :refer [*file*]]))

(defn format-bytes [bytes]
  (if (< bytes 1024)
    (str bytes " B")
    (let [kb (/ bytes 1024)]
      (if (< kb 1024)
        (str (.toFixed kb 2) " KB")
        (let [mb (/ kb 1024)]
          (str (.toFixed mb 2) " MB"))))))

(defn optimize-glb [input-path output-dir]
  (js/console.log "Optimizing" input-path)
  (p/let [output-path (path/join output-dir (path/basename input-path))
          draco-encoder (.createEncoderModule draco3d)
          io (-> (NodeIO.)
                 (.registerExtensions ALL_EXTENSIONS)
                 (.registerDependencies #js{"draco3d.encoder" draco-encoder}))
          document (.read io input-path)
          original-size (-> (fs/statSync input-path) .-size)]

    (.transform
      document
      (gf/weld)
      (gf/simplify #js {:simplifier MeshoptSimplifier :ratio 0.5 :error 0.001})
      (gf/resample)
      (gf/textureCompress #js {:encoder sharp
                               :targetFormat "webp"
                               :quality 25
                               :resize #js [512 512]})
      (gf/draco)
      (gf/prune)
      (gf/dedup))

    (.write io output-path document)

    (let [new-size (-> (fs/statSync output-path) .-size)
          reduction (if (> original-size 0)
                      (* (- 1 (/ new-size original-size)) 100)
                      0)]
      (println (str "Optimized " input-path " -> " output-path ": "
                    (format-bytes original-size) " -> " (format-bytes new-size)
                    " (" (.toFixed reduction 1) "% reduction)")))))

(def cli-options
  [["-i" "--in IN_DIR" "Input directory"
    :validate [#(fs/existsSync %) "Input directory must exist"]]
   ["-o" "--out OUT_DIR" "Output directory"]
   ["-h" "--help"]])

(defn print-usage [summary]
  (println "Usage: npx nbb tools/compress.cljs [options]")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (do
        (doseq [error errors]
          (println error))
        (print-usage summary)
        (js/process.exit 1))

      (:help options)
      (do
        (print-usage summary)
        (js/process.exit 0))

      (not (and (:in options) (:out options)))
      (do
        (println "Error: --in and --out are required.")
        (print-usage summary)
        (js/process.exit 1))

      :else
      (let [{:keys [in out]} options]
        (println "Starting GLB compression...")
        (fs/mkdirSync out #js {:recursive true})
        (p/let [_ (aget MeshoptSimplifier "ready")
                files (glob (str in "/**/*.glb"))]
          (if (empty? files)
            (println "No .glb files found to compress in" in)
            (do
              (println (str "Found " (count files) " files to compress."))
              (p/let [_ (p/all (map #(optimize-glb % out) files))]
                (println "Compression complete.")))))))))

(defn get-args [argv]
  (if *file*
    (let [argv-vec (mapv
                     #(try (fs/realpathSync %)
                           (catch :default _e %))
                     (js->clj argv))
          script-idx (.indexOf argv-vec *file*)]
      (when (>= script-idx 0)
        (not-empty (subvec argv-vec (inc script-idx)))))
    (not-empty (js->clj (.slice argv
                                (if
                                  (or
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "node_modules/nbb/cli.js")
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "/bin/nbb"))
                                  3 2))))))

(defonce started
  (apply main (not-empty (get-args js/process.argv))))
