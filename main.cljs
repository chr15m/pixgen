(ns main
  (:require ["THREE" :as THREE]
            ["OrbitControls" :as OrbitControls]
            ["GLTFLoader" :as GLTFLoader]))

(def BASE_FOV 50)
(def pixel-size 4)

(defonce state (atom {}))

(defn apply-shadows [gltf]
  (-> gltf .-scene (.traverse (fn [node]
                                (when (.-isMesh node)
                                  (aset node "castShadow" true)
                                  (aset node "receiveShadow" true)))))
  gltf)

(defn on-window-resize []
  (let [{:keys [camera renderer]} @state]
    (when (and camera renderer)
      (let [aspect (/ (.-innerWidth js/window) (.-innerHeight js/window))]
        (if (>= aspect 1)
          (set! (.-fov camera) BASE_FOV)
          (let [fov-rad (* 2 (js/Math.atan (/ (js/Math.tan (/ (* BASE_FOV js/Math.PI) 360)) aspect)))]
            (set! (.-fov camera) (/ (* fov-rad 180) js/Math.PI))))
        (set! (.-aspect camera) aspect)
        (.updateProjectionMatrix camera)
        (.setSize renderer (/ (.-innerWidth js/window) pixel-size) (/ (.-innerHeight js/window) pixel-size))
        (set! (.. renderer -domElement -style -width) (str (.-innerWidth js/window) "px"))
        (set! (.. renderer -domElement -style -height) (str (.-innerHeight js/window) "px"))))))

(defn animate []
  (js/requestAnimationFrame animate)
  (let [{:keys [renderer scene camera controls]} @state]
    (when (and renderer scene camera controls)
      (.update controls)
      (.render renderer scene camera))))

(defn init []
  (js/console.log "init...")
  (let [scene (THREE/Scene.)
        _ (set! (.-background scene) (THREE/Color. 0xf0f0f0))
        _ (aset scene "fog" (THREE/FogExp2. 0xf0f0f0 0.08))

        camera (THREE/PerspectiveCamera. 70 (/ (.-innerWidth js/window) (.-innerHeight js/window)) 0.1 100)
        _ (-> camera .-position (.set 5 5 5))

        renderer (THREE/WebGLRenderer. #js {:antialias false})
        _ (set! (.. renderer -shadowMap -enabled) true)
        _ (set! (.. renderer -shadowMap -type) THREE/PCFShadowMap)
        _ (set! (.-toneMapping renderer) THREE/ACESFilmicToneMapping)
        _ (set! (.-toneMappingExposure renderer) 1.25)
        _ (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window))
        _ (.appendChild (.-body js/document) (.-domElement renderer))

        _ (.add scene (THREE/AmbientLight. 0xffffff 1.0))

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

        controls (OrbitControls. camera (.-domElement renderer))
        _ (-> controls .-target (.set 0 0.5 0))
        _ (.update controls)

        loader (GLTFLoader.)
        _ (.load loader
                 "pred1/replicate-prediction-xfexaea6f5rj00cs9j5vqpzsx0-0.glb"
                 (fn [gltf]
                   (let [model (apply-shadows gltf)
                         scene-obj (doto (.-scene model)
                                     (-> .-scale (.multiplyScalar 5)))
                         box (doto (THREE/Box3.) (.setFromObject scene-obj))
                         center (.getCenter box (THREE/Vector3.))]
                     ; Position model to be centered and sit on the ground plane
                     (-> scene-obj .-position (.set (- (.-x center))
                                                    (- (.-y (.-min box)))
                                                    (- (.-z center))))
                     (.add scene scene-obj)
                     (swap! state assoc :model scene-obj)

                     ; Update controls to look at the model's new center
                     (.updateWorldMatrix scene-obj true)
                     (let [new-box (doto (THREE/Box3.) (.setFromObject scene-obj))
                           new-center (.getCenter new-box (THREE/Vector3.))]
                       (-> controls .-target (.copy new-center))
                       (.update controls)))))]

    (reset! state {:scene scene
                   :camera camera
                   :renderer renderer
                   :controls controls})

    (print "state:" @state)

    (.addEventListener js/window "resize" on-window-resize false)
    (on-window-resize)

    (animate)))

(init)

