(in-ns 'game.core)

(def card-definitions-identities-harishchandra-ent-where-youre-the-star
  {"Harishchandra Ent.: Where Youre the Star"
   {:effect (req (when tagged
                   (reveal-hand state :runner))
                 (add-watch state :harishchandra
                            (fn [k ref old new]
                              (when (and (is-tagged? new) (not (is-tagged? old)))
                                (system-msg ref side (str "uses Harishchandra Ent.: Where You're the Star to"
                                                          " make the Runner play with their Grip revealed"))
                                (reveal-hand state :runner))
                              (when (and (is-tagged? old) (not (is-tagged? new)))
                                (conceal-hand state :runner)))))
    :leave-play (req (when tagged
                       (conceal-hand state :runner))
                     (remove-watch state :harishchandra))}})