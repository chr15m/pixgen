(ns main
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require [promesa.core :as p]
            ["THREE" :as THREE]
            ["OrbitControls" :as OrbitControls]
            ["GLTFLoader" :as GLTFLoader]
            ["EffectComposer" :as EffectComposer]
            ["RenderPass" :as RenderPass]
            ["ShaderPass" :as ShaderPass]))

(def BASE_FOV 50)
(def pixel-size 4)

(def palettes ["31" "vinik24" "waldgeist"])
(defonce current-palette-index (atom 0))

(def MAX_PALETTE_SIZE 64)

(def default-palette
  (let [base-colors (map #(THREE/Color. %) [0x000000 0xffffff 0xff0000 0x00ff00 0x0000ff 0xffff00 0x00ffff 0xff00ff])]
    (vec (take MAX_PALETTE_SIZE (concat base-colors (repeat (THREE/Color. 0x000000)))))))

(def palette-shader-def
  {:uniforms #js {:tDiffuse #js {:value nil}
                  :u_palette #js {:value (clj->js default-palette)}
                  :u_palette_size #js {:value 8}}
   :vertexShader "
    varying vec2 vUv;
    void main() {
      vUv = uv;
      gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
    }"
   :fragmentShader "
    #define MAX_PALETTE_SIZE 64
    uniform sampler2D tDiffuse;
    uniform vec3 u_palette[MAX_PALETTE_SIZE];
    uniform int u_palette_size;
    varying vec2 vUv;

    float color_distance_sq(vec3 c1, vec3 c2) {
      vec3 d = c1 - c2;
      return dot(d, d);
    }

    void main() {
      vec4 original_color = texture2D(tDiffuse, vUv);

      if (u_palette_size == 0) {
        // DEBUG: Palette not loaded or empty, render green.
        gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
        return;
      }

      vec3 closest_color = u_palette[0];
      float min_dist_sq = 1000.0;

      for (int i = 0; i < MAX_PALETTE_SIZE; i++) {
        if (i >= u_palette_size) break;
        float dist_sq = color_distance_sq(original_color.rgb, u_palette[i]);
        if (dist_sq < min_dist_sq) {
          min_dist_sq = dist_sq;
          closest_color = u_palette[i];
        }
      }

      if (min_dist_sq == 1000.0) {
        // DEBUG: Loop ran but no color was ever closer. Should not happen. Render blue.
        gl_FragColor = vec4(0.0, 0.0, 1.0, 1.0);
        return;
      }

      gl_FragColor = vec4(closest_color, original_color.a);
    }"})

(defonce state (atom {}))

(defn load-palette [palette-name]
  (js/console.log "DEBUG: Starting to load palette" palette-name)
  (p/let [response (js/fetch (str "palettes/" palette-name ".hex"))
          text (when (.-ok response) (.text response))]
    (if text
      (let [colors (->> (.split text "\n")
                        (filter #(not= % ""))
                        (map #(js/parseInt % 16))
                        (map #(THREE/Color. %))
                        (vec))
            padded-colors (vec (take MAX_PALETTE_SIZE (concat colors (repeat (THREE/Color. 0x000000)))))
            {:keys [palette-pass]} @state]
        (js/console.log "DEBUG: Loaded palette" palette-name "with" (count colors) "colors.")
        (if palette-pass
          (do
            (js/console.log "DEBUG: palette-pass found. Current size uniform:" (-> palette-pass .-uniforms .-u_palette_size .-value))
            (set! (-> palette-pass .-uniforms .-u_palette .-value) (clj->js padded-colors))
            (set! (-> palette-pass .-uniforms .-u_palette_size .-value) (count colors))
            (js/console.log "DEBUG: Set palette uniforms. New size:" (-> palette-pass .-uniforms .-u_palette_size .-value) "New colors:" (-> palette-pass .-uniforms .-u_palette .-value)))
          (js/console.error "DEBUG: palette-pass not found in state!")))
      (js/console.error "Failed to load palette:" palette-name))))

(defn cycle-palette []
  (swap! current-palette-index #(mod (inc %) (count palettes)))
  (load-palette (nth palettes @current-palette-index)))

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

(defn update-model-name-display [text]
  (let [display-el (.getElementById js/document "model-name-display")]
    (when display-el
      (set! (.-textContent display-el) text))))

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
  (update-model-name-display (first (.split (last (.split model-path "/")) ".")))
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
    "p" (cycle-palette)
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
  (js/console.log "DEBUG: on-window-resize triggered.")
  (let [{:keys [camera renderer composer]} @state]
    (when (and camera renderer composer)
      (let [w (.-innerWidth js/window)
            h (.-innerHeight js/window)
            pw (/ w pixel-size)
            ph (/ h pixel-size)
            aspect (/ w h)]
        (js/console.log "DEBUG: Resizing to" w "x" h "(pixelated:" pw "x" ph ")")
        (if (>= aspect 1)
          (set! (.-fov camera) BASE_FOV)
          (let [fov-rad (* 2 (js/Math.atan
                               (/ (js/Math.tan (/ (* BASE_FOV js/Math.PI) 360))
                                  aspect)))]
            (set! (.-fov camera) (/ (* fov-rad 180) js/Math.PI))))
        (set! (.-aspect camera) aspect)
        (.updateProjectionMatrix camera)
        (.setSize renderer pw ph)
        (.setSize composer pw ph)
        (set! (.. renderer -domElement -style -width)
              (str w "px"))
        (set! (.. renderer -domElement -style -height)
              (str h "px"))))))

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
  ;(js/console.log "In animate")
  (let [{:keys [renderer scene camera controls model model-base-y static composer]}
        @state]
    ;(js/console.log "In animate let")
    ;(js/console.log renderer scene composer)
    (when (and renderer scene camera controls)
      ;(js/console.log "In 2")
      (when (and model model-base-y (not static))
        ;(js/console.log "In 3")
        (let [time (* (.getTime (js/Date.)) 0.002)]
          ; (js/console.log "In 4")
          (set! (-> model .-position .-y)
                (+ model-base-y (* (js/Math.sin time) 0.03)))))
      ;(js/console.log "In 5")
      (.update controls)
      ;(js/console.log "In 6")
      (if composer
        (.render composer)
        (.render renderer scene camera))
      #_ (js/console.log "animate done"))))

(defn init []
  (js/console.log "DEBUG: init started.")
  (let [scene (THREE/Scene.)
        _ (js/console.log "DEBUG: Scene created.")
        _ (set! (.-background scene) (THREE/Color. 0x303030))
        camera (THREE/PerspectiveCamera.
                 70 (/ (.-innerWidth js/window)
                       (.-innerHeight js/window))
                 0.1 100)
        _ (js/console.log "DEBUG: Camera created.")
        _ (-> camera .-position (.set 5 5 5))
        renderer (THREE/WebGLRenderer. #js {:antialias false})
        _ (js/console.log "DEBUG: Renderer created.")
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
        _ (js/console.log "DEBUG: Lights added.")
        floor (doto (THREE/Mesh. (THREE/PlaneGeometry. 20 20)
                                 (THREE/ShadowMaterial. #js {:opacity 0.3}))
                (-> .-rotation (aset "x" (* -0.5 js/Math.PI)))
                (aset "receiveShadow" true))
        _ (.add scene floor)
        _ (js/console.log "DEBUG: Floor added.")
        controls (doto (OrbitControls. camera (.-domElement renderer))
                   (-> .-target (.set 0 0.5 0))
                   (.addEventListener "change" handle-controls-change)
                   (.update))
        _ (js/console.log "DEBUG: Controls created.")
        loader (GLTFLoader.)
        composer (EffectComposer. renderer)
        _ (js/console.log "DEBUG: Composer created.")
        render-pass (RenderPass. scene camera)
        _ (.addPass composer render-pass)
        _ (js/console.log "DEBUG: RenderPass created and added.")
        palette-pass (ShaderPass. (clj->js palette-shader-def))
        _ (.addPass composer palette-pass)
        _ (js/console.log "DEBUG: ShaderPass created and added.")
        _ (js/console.log "DEBUG: Initial palette uniform value:" (-> palette-pass .-uniforms .-u_palette .-value))]

    (reset! state {:scene scene
                   :camera camera
                   :renderer renderer
                   :controls controls
                   :loader loader
                   :composer composer
                   :palette-pass palette-pass
                   :models []
                   :current-model-index -1})
    (js/console.log "DEBUG: State initialized.")

    (set-loading true)
    (load-palette "31")
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
          (update-model-name-display "3d models not found")
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
