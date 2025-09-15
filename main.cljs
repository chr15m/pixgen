(ns main
  (:require ["THREE" :as THREE]
            ["OrbitControls" :as OrbitControls]))

(def BASE_FOV 50)

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
        (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window))))))

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
        _ (set! (.-background scene) (THREE/Color. 0xe0e0e0))

        camera (THREE/PerspectiveCamera. BASE_FOV (/ (.-innerWidth js/window) (.-innerHeight js/window)) 0.1 1000)
        _ (-> camera .-position (.set 0 0 5))

        renderer (THREE/WebGLRenderer. #js {:antialias true})
        _ (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window))
        _ (.appendChild (.-body js/document) (.-domElement renderer))

        geometry (THREE/BoxGeometry. 1 1 1)
        material (THREE/MeshBasicMaterial. #js {:color 0x00ff00})
        cube (THREE/Mesh. geometry material)
        _ (.add scene cube)

        controls (OrbitControls. camera (.-domElement renderer))
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

