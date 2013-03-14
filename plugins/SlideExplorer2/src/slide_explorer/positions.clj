(ns slide-explorer.positions
  (:import (java.awt.geom Point2D$Double)
           (javax.swing.event ChangeListener))
  (:require [slide-explorer.user-controls :as user-controls]
            [slide-explorer.affine :as affine]
            [org.micromanager.mm :as mm]))

(def position-lock (Object.))

(defn get-position-list-coords [affine-stage-to-pixel]
  (for [pos (mm/get-positions)]
    (let [{:keys [x y label]} (-> pos bean)]
      (-> (Point2D$Double. x y)
          (affine/transform affine-stage-to-pixel)
          bean
          (select-keys [:x :y])
          (assoc :label label)))))

(defn grid-distances [pos0 pos1]
  (merge-with #(Math/abs (- %1 %2)) pos0 pos1))

(defn tile-distances [grid-distances w h]
  (merge-with / grid-distances {:x w :y h}))

(defn in-tile [{:keys [x y] :as tile-distances}]
  (and (>= 1/2 x) (>= 1/2 y)))

(defn position-clicked [available-positions pos w h]
  (first
    (filter #(-> %
                 (grid-distances pos)
                 (tile-distances w h)
                 (in-tile))
            available-positions)))

(defn add-position-to-list [screen-state-atom position-map affine-stage-to-pixel]
  (let [{:keys [x y]} position-map
        [x1 y1] (affine/inverse-transform [x y] affine-stage-to-pixel)
        {:keys [z-origin slice-size-um z]} @screen-state-atom
        zpos (+ z-origin (* z slice-size-um))
        label (str "Pos" (int (* 100000 (rand))))
        labeled-position (assoc position-map :label label)]
    (mm/add-msp label x1 y1 zpos)
    (swap! screen-state-atom update-in [:positions] conj labeled-position)))

(defn remove-position-from-list [screen-state-atom position-map affine-stage-to-pixel]
  (let [positions (get-position-list-coords affine-stage-to-pixel)
        label-indices (zipmap (map :label positions) (range (count positions)))
        dead-label (:label position-map)
        dead-index (label-indices dead-label)]
    (when dead-index
      (mm/remove-msp dead-index)))
  (swap! screen-state-atom update-in [:positions] disj position-map))

(defn toggle-position [screen-state-atom _ _ affine-stage-to-pixel]
  (let [pos (user-controls/absolute-mouse-position @screen-state-atom)
        [w h] (:tile-dimensions @screen-state-atom)]
    (locking position-lock
      (if-let [old-pos (position-clicked (:positions @screen-state-atom) pos w h)]
        (remove-position-from-list screen-state-atom old-pos affine-stage-to-pixel)
        (add-position-to-list screen-state-atom pos affine-stage-to-pixel)))))

(defn update-positions-atom! [screen-state-atom affine-stage-to-pixel]
  (locking position-lock
  (let [external-value (set (get-position-list-coords affine-stage-to-pixel))
        internal-value (:positions @screen-state-atom)]
    (when (not= external-value internal-value)
      (swap! screen-state-atom assoc :positions external-value)))))

(defn follow-positions! [screen-state-atom affine-stage-to-pixel]
  (.addChangeListener (.. mm/gui getPositionList)
    (proxy [ChangeListener] []
      (stateChanged [_]
                    (update-positions-atom! screen-state-atom
                                            affine-stage-to-pixel)))))

(defn handle-positions [panel screen-state-atom affine-stage-to-pixel]
  (update-positions-atom! screen-state-atom affine-stage-to-pixel)
  (user-controls/handle-control-click
    panel
    (fn [x y] (toggle-position screen-state-atom x y affine-stage-to-pixel)))
  (follow-positions! screen-state-atom affine-stage-to-pixel))
