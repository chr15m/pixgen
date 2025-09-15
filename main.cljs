(ns main
  (:require ["THREE" :as THREE]
            ["OrbitControls" :as OrbitControls]))

(def BASE_FOV 50)
(def pixel-size 4)

(defonce state (atom {}))

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
  (let [{:keys [cube renderer scene camera controls]} @state]
    (when (and cube renderer scene camera controls)
      (set! (.-rotation.x cube) (+ (.-rotation.x cube) 0.01))
      (set! (.-rotation.y cube) (+ (.-rotation.y cube) 0.01))
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

        geometry (THREE/BoxGeometry. 1 1 1)
        material (THREE/MeshStandardMaterial. #js {:color 0x00ff00})
        cube (doto (THREE/Mesh. geometry material)
               (-> .-position (.set 0 1 0))
               (aset "castShadow" true)
               (aset "receiveShadow" true))
        _ (.add scene cube)

        floor (doto (THREE/Mesh. (THREE/PlaneGeometry. 20 20)
                                 (THREE/ShadowMaterial. #js {:opacity 0.3}))
                (-> .-rotation .-x (* -0.5 js/Math.PI))
                (aset "receiveShadow" true))
        _ (.add scene floor)

        controls (OrbitControls. camera (.-domElement renderer))
        _ (-> controls .-target (.set 0 1 0))
        _ (.update controls)]

    (reset! state {:scene scene
                   :camera camera
                   :renderer renderer
                   :cube cube
                   :controls controls})

    (print "state:" @state)

    (.addEventListener js/window "resize" on-window-resize false)
    (on-window-resize)

    (animate)))

(init)

