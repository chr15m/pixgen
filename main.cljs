(ns main
  (:require ["THREE" :as THREE]))

(js/console.log "THREE" THREE)

(defonce state (atom {:frame-count 0}))
(defonce animated (atom false))

(defn on-window-resize []
  (let [{:keys [camera renderer]} @state]
    (when (and camera renderer)
      (set! (.-aspect camera) (/ (.-innerWidth js/window) (.-innerHeight js/window)))
      (.updateProjectionMatrix camera)
      (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window)))))

(defn animate []
  (js/requestAnimationFrame animate)
  (let [{:keys [cube renderer scene camera]} @state]
    (when (and cube renderer scene camera)
      (swap! state update :frame-count inc)
      (when-not @animated
        (js/console.log "first animation frame")
        (reset! animated true))
      (when (zero? (mod (:frame-count @state) 100))
        (js/console.log "frame:" (:frame-count @state))
        (js/console.log "camera position:" (.-position camera))
        (js/console.log "cube position:" (.-position cube)))
      (set! (.-rotation.x cube) (+ (.-rotation.x cube) 0.01))
      (set! (.-rotation.y cube) (+ (.-rotation.y cube) 0.01))
      (.render renderer scene camera))))

(defn init []
  (js/console.log "init...")
  (let [scene (THREE/Scene.)
        _ (set! (.-background scene) (THREE/Color. 0xe0e0e0))
        _ (js/console.log "scene" scene)

        camera (THREE/PerspectiveCamera. 75 (/ (.-innerWidth js/window) (.-innerHeight js/window)) 0.1 1000)
        _ (-> camera .-position (.set 0 0 5))
        _ (js/console.log "camera" camera)

        renderer (THREE/WebGLRenderer. #js {:antialias true})
        _ (.setSize renderer (.-innerWidth js/window) (.-innerHeight js/window))
        _ (.appendChild (.-body js/document) (.-domElement renderer))
        _ (js/console.log "renderer" renderer)

        geometry (THREE/BoxGeometry. 1 1 1)
        _ (js/console.log "geometry" geometry)
        material (THREE/MeshBasicMaterial. #js {:color 0x00ff00})
        _ (js/console.log "material" material)
        cube (THREE/Mesh. geometry material)
        _ (js/console.log "cube" cube)
        _ (.add scene cube)
        _ (js/console.log "scene after add" scene)]

    (swap! state merge {:scene scene
                        :camera camera
                        :renderer renderer
                        :cube cube})

    (print "state:" @state)

    (.addEventListener js/window "resize" on-window-resize false)

    (animate)))

(init)

