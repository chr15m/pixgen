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
    [promesa.core :as p]))

(defn format-bytes [bytes]
  (if (< bytes 1024)
    (str bytes " B")
    (let [kb (/ bytes 1024)]
      (if (< kb 1024)
        (str (.toFixed kb 2) " KB")
        (let [mb (/ kb 1024)]
          (str (.toFixed mb 2) " MB"))))))

(defn optimize-glb [input-path]
  (js/console.log "Optimizing" input-path)
  (p/let [ext (path/extname input-path)
          basename (path/basename input-path ext)
          dirname (path/dirname input-path)
          output-path (path/join dirname (str basename "-compressed" ext))
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

(defn main []
  (println "Starting GLB compression...")
  (p/let [_ (js/console.log "Before simplifier")
          _ (aget MeshoptSimplifier "ready")
          _ (js/console.log "After simplifier")
          files (glob "assets/generated/**/*.glb")]
    (js/console.log "Found files:")
    (js/console.log files)
    (if (empty? files)
      (println "No .glb files found to compress.")
      (do
        (println (str "Found " (count files) " files to compress."))
        (p/let [_ (p/all (map optimize-glb files))]
          (println "Compression complete."))))))

(main)
