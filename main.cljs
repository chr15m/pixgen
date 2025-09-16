(ns main
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require [promesa.core :as p]
            ["THREE" :as THREE]
            ["OrbitControls" :as OrbitControls]
            ["GLTFLoader" :as GLTFLoader]))

(def BASE_FOV 50)
(def pixel-size 4)

(defonce state (atom {}))

(def DRAG_THRESHOLD 5)
(defonce mouse-down-pos (atom nil))

(defn apply-shadows [gltf & {:keys [brighten]}]
  (-> gltf .-scene
      (.traverse (fn [node]
                   (when (.-isMesh node)
                     (aset node "castShadow" true)
                     (aset node "receiveShadow" true)
                     (when (.-material node)
                       ;(set! (.-color (.-material node)) (THREE/Color. 0xffffff))
                       (-> node .-material .-color (.multiplyScalar
                                                     (or brighten 1)))
                       (set! (.-metalness (.-material node)) 0.01)
                       (set! (.-roughness (.-material node)) 0.4))))))
  gltf)

(defn set-loading [loading?]
  (let [spinner (.getElementById js/document "spinner")]
    (js/console.log "loading?" loading?)
    (when spinner
      (aset spinner "style" "display"
            (if loading? "block" "none")))))

(defn update-model-name-display [model-path]
  (let [display-el (.getElementById js/document "model-name-display")]
    (when display-el
      (let [file-name (last (.split model-path "/"))
            base-name (first (.split file-name "."))]
        (set! (.-textContent display-el) base-name)))))

(defn remove-scenery [scene]
  (let [scenery-to-remove (->> (.-children scene)
                               (filter #(aget % "isScenery"))
                               (vec))]
    (doseq [child scenery-to-remove]
      (.remove scene child))))

(defn load-and-place-scenery []
  (let [{:keys [scene loader scenery-models]} @state
        model-name (rand-nth scenery-models)]
    (when model-name
      (.load loader (str "models/" model-name)
             (fn [gltf]
               (let [model (apply-shadows gltf)
                     scene-obj (.-scene model)
                     angle (* (js/Math.random) js/Math.PI 2)
                     radius (+ 4 (* (js/Math.random) 3))
                     x (* radius (js/Math.cos angle))
                     z (* radius (js/Math.sin angle))
                     scale (if (.includes model-name "tree")
                             (+ 1 (* (js/Math.random) 2))
                             1)]
                 (aset scene-obj "isScenery" true)
                 (-> scene-obj .-position (.set x 0 z))
                 (-> scene-obj .-scale (.set scale scale scale))
                 (-> scene-obj .-rotation
                     (.set 0 (* (js/Math.random) js/Math.PI 2) 0))
                 (.add scene scene-obj)))
             nil
             (fn [error]
               (js/console.error "Error loading scenery:" model-name error)))))) 

(defn load-model [model-path]
  (set-loading true)
  (update-model-name-display model-path)
  (let [{:keys [scene loader model]} @state]
    (when model
      (.remove scene model))
    (remove-scenery scene)
    (dotimes [_ 15] (load-and-place-scenery))
    (.load loader
           (str "models/" model-path)
           (fn [gltf]
             (let [{:keys [models current-model-index]} @state
                   current-model-path (nth models current-model-index)]
               (if (not= model-path current-model-path)
                 (do
                   (js/console.log "Model changed while loading, discarding:" model-path)
                   (.remove scene (.-scene gltf)))
                 (let [model (apply-shadows gltf {:brighten 5})
                       scene-obj (.-scene model)
                       static (not (.includes model-path "float"))
                       temp-box (doto (THREE/Box3.) (.setFromObject scene-obj))
                       size (.getSize temp-box (THREE/Vector3.))
                       max-dim (js/Math.max (.-x size) (.-y size) (.-z size))
                       scale-factor (if (> max-dim 0) (/ 5 max-dim) 1)
                       _ (-> scene-obj .-scale
                             (.set scale-factor scale-factor scale-factor))
                       box (doto (THREE/Box3.) (.setFromObject scene-obj))
                       center (.getCenter box (THREE/Vector3.))
                       base-y (- (- (.-y (.-min box))
                                    (if static 0 1)))]
                   (-> scene-obj .-position (.set (- (.-x center))
                                                  base-y
                                                  (- (.-z center))))
                   (.add scene scene-obj)
                   (swap! state assoc
                          :model scene-obj
                          :static static
                          :model-base-y base-y)
                   (let [{:keys [controls]} @state]
                     (.updateWorldMatrix scene-obj true)
                     (let [new-box (doto (THREE/Box3.) (.setFromObject scene-obj))
                           new-center (.getCenter new-box (THREE/Vector3.))]
                       (-> controls .-target (.copy new-center))
                       (.update controls))))))
             (set-loading false))))
  (fn [error]
    (js/console.error "Error loading model:" model-path error)
    (set-loading false)))

(defn change-model [delta]
  (let [{:keys [models current-model-index]} @state]
    (when (pos? (count models))
      (let [new-index (mod (+ current-model-index delta) (count models))]
        (when (not= new-index current-model-index)
          (swap! state assoc :current-model-index new-index)
          (load-model (nth models new-index)))))))

(defn handle-key-down [event]
  (case (.-key event)
    ("ArrowRight" "PageDown" " " "Enter") (change-model 1)
    ("ArrowLeft" "PageUp") (change-model -1)
    nil))

(defn handle-mouse-down [event]
  (let [source (if (.-changedTouches event) (aget (.-changedTouches event) 0) event)]
    (reset! mouse-down-pos {:x (.-clientX source) :y (.-clientY source)})))

(defn handle-mouse-up [event]
  (when-let [down-pos @mouse-down-pos]
    (let [source (if (.-changedTouches event) (aget (.-changedTouches event) 0) event)
          up-x (.-clientX source)
          up-y (.-clientY source)
          dx (- up-x (:x down-pos))
          dy (- up-y (:y down-pos))
          distance (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
      (when (< distance DRAG_THRESHOLD)
        (change-model 1)))
    (reset! mouse-down-pos nil)))

(defn on-window-resize []
  (let [{:keys [camera renderer]} @state]
    (when (and camera renderer)
      (let [aspect (/ (.-innerWidth js/window) (.-innerHeight js/window))]
        (if (>= aspect 1)
          (set! (.-fov camera) BASE_FOV)
          (let [fov-rad (* 2 (js/Math.atan
                               (/ (js/Math.tan (/ (* BASE_FOV js/Math.PI) 360))
                                  aspect)))]
            (set! (.-fov camera) (/ (* fov-rad 180) js/Math.PI))))
        (set! (.-aspect camera) aspect)
        (.updateProjectionMatrix camera)
        (.setSize renderer
                  (/ (.-innerWidth js/window) pixel-size)
                  (/ (.-innerHeight js/window) pixel-size))
        (set! (.. renderer -domElement -style -width)
              (str (.-innerWidth js/window) "px"))
        (set! (.. renderer -domElement -style -height)
              (str (.-innerHeight js/window) "px"))))))

(defn handle-controls-change []
  (let [{:keys [controls camera]} @state]
    (when (and controls camera)
      ; Prevent target from going below ground
      (set! (-> controls .-target .-y) (js/Math.max (-> controls .-target .-y) 0))

      (let [center-position (.clone (.-target controls))
            _ (set! (.-y center-position) 0)
            ground-position (.clone (.-position camera))
            _ (set! (.-y ground-position) 0)
            d (.distanceTo center-position ground-position)
            origin (THREE/Vector2. (-> controls .-target .-y) 0)
            remote (THREE/Vector2. 0 d)
            angle-radians (js/Math.atan2 (- (.-y remote) (.-y origin))
                                         (- (.-x remote) (.-x origin)))]
        (set! (.-maxPolarAngle controls) angle-radians)))))

(defn animate []
  (js/requestAnimationFrame animate)
  (let [{:keys [renderer scene camera controls model model-base-y static]}
        @state]
    (when (and renderer scene camera controls)
      (when (and model model-base-y (not static))
        (let [time (* (.getTime (js/Date.)) 0.002)]
          (set! (-> model .-position .-y)
                (+ model-base-y (* (js/Math.sin time) 0.03)))))
      (.update controls)
      (.render renderer scene camera))))

(defn init []
  (js/console.log "init...")
  (let [scene (THREE/Scene.)
        _ (set! (.-background scene) (THREE/Color. 0x303030))
        camera (THREE/PerspectiveCamera.
                 70 (/ (.-innerWidth js/window)
                       (.-innerHeight js/window))
                 0.1 100)
        _ (-> camera .-position (.set 5 5 5))
        renderer (THREE/WebGLRenderer. #js {:antialias false})
        _ (set! (.. renderer -shadowMap -enabled) true)
        _ (set! (.. renderer -shadowMap -type) THREE/PCFShadowMap)
        _ (set! (.-toneMapping renderer) THREE/NoToneMapping)
        _ (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window))
        _ (.appendChild (.-body js/document) (.-domElement renderer))
        _ (.add scene (THREE/AmbientLight. 0xffffff 0.75))
        _ (.add scene (doto (THREE/SpotLight. 0xffffff 1.0)
                        (-> .-position (.set 10 20 10))
                        (aset "castShadow" true)
                        (-> .-shadow .-mapSize (.set 1024 1024))))
        _ (.add scene (doto (THREE/DirectionalLight. 0xffffff 0.5)
                        (-> .-position (.set -20 20 20))))
        floor (doto (THREE/Mesh. (THREE/PlaneGeometry. 20 20)
                                 (THREE/ShadowMaterial. #js {:opacity 0.3}))
                (-> .-rotation (aset "x" (* -0.5 js/Math.PI)))
                (aset "receiveShadow" true))
        _ (.add scene floor)
        controls (doto (OrbitControls. camera (.-domElement renderer))
                   (-> .-target (.set 0 0.5 0))
                   (.addEventListener "change" handle-controls-change)
                   (.update))
        loader (GLTFLoader.)]

    (reset! state {:scene scene
                   :camera camera
                   :renderer renderer
                   :controls controls
                   :loader loader
                   :models []
                   :current-model-index -1})

    (set-loading true)
    (p/let [response (js/fetch "models/directory.json")
            dir-data (when (.-ok response) (.json response))]
      (if dir-data
        (let [root-contents (-> dir-data first (aget "contents"))
              generated-dir (first (filter #(= (aget % "name") "./generated") root-contents))
              scenery-dir (first (filter #(= (aget % "name") "./kenney-nature-kit") root-contents))
              model-files (when generated-dir
                            (->> (aget generated-dir "contents")
                                 (filter #(and (= (aget % "type") "file") (.endsWith (aget % "name") ".glb")))
                                 (map #(aget % "name"))
                                 (map #(.substring % 2))
                                 (sort)
                                 (vec)))
              scenery-files (when scenery-dir
                              (->> (aget scenery-dir "contents")
                                   (filter #(and (= (aget % "type") "file") (.endsWith (aget % "name") ".glb")))
                                   (map #(aget % "name"))
                                   (map #(.substring % 2))
                                   (vec)))]
          (swap! state assoc :scenery-models scenery-files)
          (if (and model-files (pos? (count model-files)))
            (do
              (swap! state assoc
                     :models model-files
                     :current-model-index 0)
              (load-model (first model-files)))
            (set-loading false)))
        (do
          (js/console.error "Failed to load models/directory.json")
          (set-loading false))))

    (.addEventListener js/window "resize" on-window-resize false)
    (on-window-resize)
    (.addEventListener js/window "keydown" handle-key-down false)
    (.addEventListener js/window "mousedown" handle-mouse-down false)
    (.addEventListener js/window "mouseup" handle-mouse-up false)
    (.addEventListener js/window "touchstart" handle-mouse-down false)
    (.addEventListener js/window "touchend" handle-mouse-up false)

    (animate)))

(init)
