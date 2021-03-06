(in-ns 'game.core)

(declare any-flag-fn? clear-run-register! run-cleanup
         gain-run-credits update-ice-in-server update-all-ice
         get-agenda-points gain-agenda-point optional-ability7
         get-remote-names card-name can-access-loud can-steal?
         prevent-jack-out card-flag? can-run?)

;;; Steps in the run sequence
(defn run
  "Starts a run on the given server, with the given card as the cause."
  ([state side server] (run state side (make-eid state) server nil nil))
  ([state side eid server] (run state side eid server nil nil))
  ([state side server run-effect card] (run state side (make-eid state) server run-effect card))
  ([state side eid server run-effect card]
   (when (can-run? state :runner)
     (let [s [(if (keyword? server) server (last (server->zone state server)))]
           ices (get-in @state (concat [:corp :servers] s [:ices]))
           n (count ices)]
       ;; s is a keyword for the server, like :hq or :remote1
       (swap! state assoc :per-run nil
              :run {:server s :position n :access-bonus 0
                    :run-effect (assoc run-effect :card card)
                    :eid eid})
       (gain-run-credits state side (+ (get-in @state [:corp :bad-publicity]) (get-in @state [:corp :has-bad-pub])))
       (swap! state update-in [:runner :register :made-run] #(conj % (first s)))
       (update-all-ice state :corp)
       (trigger-event-sync state :runner (make-eid state) :run s)
       (when (>= n 2) (trigger-event state :runner :run-big s n))))))

(defn gain-run-credits
  "Add temporary credits that will disappear when the run is over."
  [state side n]
  (swap! state update-in [:runner :run-credit] + n)
  (gain state :runner :credit n))

;;; Stealing agendas
(defn steal
  "Moves a card to the runner's :scored area, triggering events from the completion of the steal."
  ([state side card] (steal state side (make-eid state) card))
  ([state side eid card]
   (let [c (move state :runner (dissoc card :advance-counter :new) :scored {:force true})
         points (get-agenda-points state :runner c)]
     (when-completed
       (trigger-event-simult
         state :runner :agenda-stolen
         {:first-ability {:effect (req (system-msg state :runner (str "steals " (:title c) " and gains "
                                                                      (quantify points "agenda point")))
                                       (swap! state update-in [:runner :register :stole-agenda]
                                              #(+ (or % 0) (:agendapoints c)))
                                       (gain-agenda-point state :runner points)
                                       (play-sfx state side "agenda-steal")
                                       (when (:run @state)
                                         (swap! state assoc-in [:run :did-steal] true))
                                       (when (card-flag? c :has-events-when-stolen true)
                                         (register-events state side (:events (card-def c)) c))
                                       (remove-old-current state side :corp))}
          :card-ability (ability-as-handler c (:stolen (card-def c)))}
         c)
       (effect-completed state side eid nil)))))

(defn- resolve-steal-events
  "Trigger events from accessing an agenda, which were delayed to account for Film Critic."
  ([state side card] (resolve-steal-events state side (make-eid state) card))
  ([state side eid card]
   (trigger-event state side :access card)
   (effect-completed state side eid card)))

(defn- resolve-steal
  "Finish the stealing of an agenda."
  ([state side card] (resolve-steal state side (make-eid state) card))
  ([state side eid card]
   (let [cdef (card-def card)]
     (when-completed (resolve-steal-events state side card)
                     (if (or (not (:steal-req cdef)) ((:steal-req cdef) state :runner (make-eid state) card nil))
                       (steal state :runner eid card)
                       (effect-completed state side eid nil))))))

(defn steal-cost-bonus
  "Applies a cost to the next steal attempt. costs can be a vector of [:key value] pairs,
  for example [:credit 2 :click 1]."
  [state side costs]
  (swap! state update-in [:bonus :steal-cost] #(merge-costs (concat % costs))))

(defn steal-cost
  "Gets a vector of costs for stealing the given agenda."
  [state side card]
  (-> (when-let [costfun (:steal-cost-bonus (card-def card))]
        (costfun state side (make-eid state) card nil))
      (concat (get-in @state [:bonus :steal-cost]))
      merge-costs flatten vec))

;;; Accessing rules.
(defn access-cost-bonus
  "Applies a cost to the next access. costs can be a vector of [:key value] pairs,
  for example [:credit 2 :click 1]."
  [state side costs]
  (swap! state update-in [:bonus :access-cost] #(merge-costs (concat % costs))))

(defn access-cost
  "Gets a vector of costs for accessing the given card."
  [state side card]
  (-> (when-let [costfun (:access-cost-bonus (card-def card))]
        (costfun state side (make-eid state) card nil))
      (concat (get-in @state [:bonus :access-cost]))
      merge-costs flatten vec))

(defn- access-non-agenda
  [state side eid c]
  (trigger-event state side :access c)
  (trigger-event state side :pre-trash c)
  (if (not= (:zone c) [:discard]) ; if not accessing in Archives
    (if-let [trash-cost (trash-cost state side c)]
      ;; The card has a trash cost (Asset, Upgrade)
      (let [card (assoc c :seen true)
            name (:title card)
            trash-msg (str trash-cost " [Credits] to trash " name " from " (name-zone :corp (:zone card)))]
        (if (and (get-in @state [:runner :register :force-trash])
                 (can-pay? state :runner name :credit trash-cost))
          ;; If the runner is forced to trash this card (Neutralize All Threats)
          (continue-ability state :runner
                            {:cost [:credit trash-cost]
                             :delayed-completion true
                             :effect (req (when (:run @state)
                                            (swap! state assoc-in [:run :did-trash] true)
                                            (swap! state assoc-in [:run :did-access] true))
                                          (swap! state assoc-in [:runner :register :trashed-card] true)
                                          (trash state side eid card nil)
                                          (system-msg state side (str "is forced to pay " trash-msg)))}
                            card nil)
          ;; Otherwise, show the option to pay to trash the card.
          (when-not (and (is-type? card "Operation")
                         ;; Don't show the option if Edward Kim's auto-trash flag is true.
                         (card-flag? card :can-trash-operation true))
            ;; If card has already been trashed this access don't show option to pay to trash (eg. Ed Kim)
            (when-not (find-cid (:cid card) (get-in @state [:corp :discard]))
              (continue-ability state :runner
                                {:optional
                                 {:delayed-completion true
                                  :prompt (str "Pay " trash-cost " [Credits] to trash " name "?")
                                  :no-ability {:effect (req
                                                         ;; toggle access flag to prevent Hiro issue #2638
                                                         (swap! state dissoc :access)
                                                         (trigger-event state side :no-trash c)
                                                         (swap! state assoc :access true)
                                                         (effect-completed state side eid))}
                                  :yes-ability {:cost [:credit trash-cost]
                                                :delayed-completion true
                                                :effect (req (when (:run @state)
                                                               (swap! state assoc-in [:run :did-trash] true))
                                                             (swap! state assoc-in [:runner :register :trashed-card] true)
                                                             (trash state side eid card nil)
                                                             (system-msg state side (str "pays " trash-msg)))}}}
                                card nil)))))
      ;; The card does not have a trash cost
      (do (prompt! state :runner c (str "You accessed " (:title c)) ["OK"] {:eid eid})
          ;; TODO: Trigger :no-trash after hit "OK" on access
          (when-not (find-cid (:cid c) (get-in @state [:corp :discard]))
            ;; Do not trigger :no-trash if card (operation) has already been trashed
            (trigger-event state side :no-trash c))))
    (effect-completed state side eid)))

(defn- steal-pay-choice
  "Enables a vector of costs to be resolved in the order of choosing"
  [state side choices chosen n card]
  {:delayed-completion true
   :prompt "Pay steal cost?"
   :choices (conj (vec choices) "Don't steal")
   :effect (req
             (if (= target "Don't steal")
               (continue-ability state :runner
                                 {:delayed-completion true
                                  :effect (req (when-not (find-cid (:cid card) (:deck corp))
                                                    (system-msg state side (str "decides not to pay to steal " (:title card))))
                                                  (trigger-event state side :no-steal card)
                                                  (resolve-steal-events state side eid card))} card nil)
               (let [name (:title card)
                     chosen (cons target chosen)
                     clicks (count (re-seq #"\[Click\]+" target))
                     kw (if (pos? clicks) :click (to-keyword (join "-" (rest (split target #" ")))))
                     val (if (pos? clicks) clicks (string->num (first (split target #" "))))]
                 (if (can-pay? state side name [kw val])
                   (when-completed
                     (pay-sync state side nil [kw val] {:action :steal-cost})
                     (do (system-msg state side (str "pays " target
                                                   " to steal " (:title card)))
                         (if (< (count chosen) n)
                           (continue-ability
                             state side
                             (steal-pay-choice state :runner (remove-once #(= target %) choices) chosen n card)
                             card nil)
                           (resolve-steal state side eid card))))
                   (resolve-steal-events state side eid card)))))})

(defn- access-agenda
  [state side eid c]
  (trigger-event state side :pre-steal-cost c)
  (if-not (can-steal? state side c)
    ;; The runner cannot steal this agenda.
    (when-completed (resolve-steal-events state side c)
                    (do (prompt! state :runner c (str "You accessed but cannot steal " (:title c)) ["OK"] {})
                        (trigger-event state side :no-steal c)
                        (effect-completed state side eid c)))
    ;; The runner can potentially steal this agenda.
    (let [cost (steal-cost state side c)
          name (:title c)
          choices (map costs-to-symbol (partition 2 cost))
          n (count choices)]
      ;; Steal costs are additional costs and can be denied by the runner.
      (cond
        ;; Ask if the runner will pay a single additional cost to steal.
        (= 1 (count choices))
        (optional-ability
          state :runner eid c (str "Pay " (costs-to-symbol cost) " to steal " name "?")
          {:yes-ability
                       {:delayed-completion true
                        :effect (req (if (can-pay? state side name cost)
                                       (when-completed (pay-sync state side nil cost {:action :steal-cost})
                                                       (do (system-msg state side (str "pays " (costs-to-symbol cost)
                                                                                   " to steal " name))
                                                           (resolve-steal state side eid c)))
                                       (do (trigger-event state side :no-steal card)
                                           (resolve-steal-events state side eid c))))}
           :no-ability {:delayed-completion true
                        :effect (effect (trigger-event :no-steal card)
                                        (resolve-steal-events eid c))}}
          nil)

        ;; For multiple additional costs give the runner the choice of order to pay
        (> (count choices) 1)
        (continue-ability state side (steal-pay-choice state :runner choices '() n c) c nil)

        ;; Otherwise, show the "You access" prompt with the single option to Steal.
        :else
        (continue-ability state :runner
                          {:delayed-completion true
                           :prompt (str "You access " name) :choices ["Steal"]
                           :effect (req (resolve-steal state :runner eid c))} c nil)))))

(defn- reveal-access?
  "Check if the card should be revealed on access"
  ;; If any special reveal message is wanted it can go in this function
  [state side {:keys [zone] :as card}]
  (let [cdef (card-def card)
        ;; Add more kw here as the maybe become relevant. Only think rd is relevant,
        ;; everything else should not be "unseen".
        reveal-kw (match (vec zone)
                         [:deck] :rd-reveal
                         [:hand] :hq-reveal
                         [:discard] :archives-reveal
                         :else :reveal)]
    ;; Check if the zone-reveal keyword exists in the flags property of the card definition
    (when-let [reveal-fn (get-in cdef [:flags reveal-kw])]
      (reveal-fn state side (make-eid state) card nil))))

(defn msg-handle-access
  "Generate the message from the access"
  [state side {:keys [zone] :as card} title]
  (let [msg (str "accesses " title
                 (when card
                   (str " from " (name-zone side zone))))]
    (system-msg state side msg)
    (when (reveal-access? state side card)
      (system-msg state side (str "must reveal they accessed " (:title card))))))

(defn- resolve-handle-access
  [state side eid c title]
  (let [cdef (card-def c)
        c (assoc c :seen true)
        access-effect (:access cdef)]
    (msg-handle-access state side c title)
    (when-let [name (:title c)]
      (if (is-type? c "Agenda")
        ;; Accessing an agenda
        (if (and access-effect
                 (can-trigger? state side access-effect c nil))
          ;; deal with access effects first. This is where Film Critic can be used to prevent these
          (continue-ability state :runner
                            {:delayed-completion true
                             :prompt             (str "You must access " name)
                             :choices            ["Access"]
                             :effect             (req (when-completed
                                                        (resolve-ability state (to-keyword (:side c)) access-effect c nil)
                                                        (access-agenda state side eid c)))} c nil)
          (access-agenda state side eid c))
        ;; Accessing a non-agenda
        (if (and access-effect
                 (= (:zone c) (:zone (get-card state c))))
          ;; if card wasn't moved by a pre-access effect
          (when-completed (resolve-ability state (to-keyword (:side c)) access-effect c nil)
                          (if (= (:zone c) (:zone (get-card state c)))
                            ;; if the card wasn't moved by the access effect
                            (access-non-agenda state side eid c)
                            (effect-completed state side eid)))
          (access-non-agenda state side eid c))))))

(defn- handle-access-pay
  [state side eid c title]
  (let [acost (access-cost state side c)
        ;; hack to prevent toasts when playing against Gagarin and accessing on 0 credits
        anon-card (dissoc c :title)
        cant-pay #(prompt! state :runner nil "You can't pay the cost to access this card" ["OK"] {})]
    (cond
      ;; Check if a pre-access-card effect trashed the card (By Any Means)
      (not (get-card state c))
      (effect-completed state side eid)

      ;; Either there were no access costs, or the runner could pay them.
      (empty? acost)
      (resolve-handle-access state side eid c title)

      (not-empty acost)
      (when-completed (pay-sync state side anon-card acost)
                      (if async-result
                        (resolve-handle-access state side eid c title)
                        (cant-pay)))
      :else
      ;; The runner cannot afford the cost to access the card
      (cant-pay)))
  (trigger-event state side :post-access-card c))

(defn handle-access
  "Apply game rules for accessing the given list of cards (which generally only contains 1 card.)"
  ([state side cards] (handle-access state side (make-eid state) cards nil))
  ([state side eid cards] (handle-access state side eid cards (:title (first cards))))
  ([state side eid cards title]
   (swap! state assoc :access true)
   (doseq [c cards]
     ;; Reset counters for increasing costs of trash, steal, and access.
     (swap! state update-in [:bonus] dissoc :trash)
     (swap! state update-in [:bonus] dissoc :steal-cost)
     (swap! state update-in [:bonus] dissoc :access-cost)
     (when-completed (trigger-event-sync state side :pre-access-card c)
                     (handle-access-pay state side eid c title)))))

(defn max-access
  "Put an upper limit on the number of cards that can be accessed in this run. For Eater."
  [state side n]
  (swap! state assoc-in [:run :max-access] n))

(defn access-bonus
  "Increase the number of cards to be accessed during this run by n. Legwork, Maker's Eye.
  Not for permanent increases like RDI."
  [state side n]
  (swap! state update-in [:run :access-bonus] (fnil #(+ % n) 0)))

(defn access-count [state side kw]
  (let [run (:run @state)
        accesses (+ (get-in @state [:runner kw]) (:access-bonus run 0))]
    (if-let [max-access (:max-access run)]
      (min max-access accesses) accesses)))


;;; Methods for allowing user-controlled multi-access in servers.

;; choose-access implements game prompts allowing the runner to choose the order of access.
(defmulti choose-access (fn [cards server] (get-server-type (first server))))

(defn access-helper-remote [cards]
  {:prompt "Click a card to access it. You must access all cards in this server."
   :choices {:req #(some (fn [c] (= (:cid %) (:cid c))) cards)}
   :delayed-completion true
   :effect (req (when-completed (handle-access state side [target])
                                (if (< 1 (count cards))
                                  (continue-ability state side (access-helper-remote (filter #(not= (:cid %) (:cid target)) cards))
                                                    card nil)
                                  (effect-completed state side eid nil))))})

(defmethod choose-access :remote [cards server]
  {:delayed-completion true
   :effect (req (if (and (>= 1 (count cards))
                         (not (any-flag-fn? state :runner :slow-remote-access true
                                            (concat (all-active state :runner) (all-active state :corp)))))
                  (handle-access state side eid cards)
                  (continue-ability state side (access-helper-remote cards) card nil)))})

(defn access-helper-hq-or-rd
  "Shows a prompt to access card(s) from the given zone.
  zone: :rd or :hq, for finding Upgrades to access.
  label: a string label to describe what is being accessed, e.g., 'Card from deck' -- 'deck' being the label.
  amount: how many accesses the runner has remaining.
  select-fn: a function taking the already-accessed set as argument, and returning the next card to access
      from the given zone.
  title-fn: a function taking a card map being accessed and returning a string to print as the card's title, e.g.,
      'an unseen card from R&D' for an R&D run.
  already-accessed: a set of cards already accessed from this zone or its root."
  [state chosen-zone label amount select-fn title-fn already-accessed]
  (let [get-root-content (fn [state]
                           (filter #(not (contains? already-accessed %)) (get-in @state [:corp :servers chosen-zone :content])))
        server-name (central->name chosen-zone)
        unrezzed-upgrade (str "Unrezzed upgrade in " server-name)
        card-from (str "Card from " label)]
    {:delayed-completion true
     :prompt "Select a card to access."
     :choices (concat (when (pos? amount) [card-from])
                      (map #(if (rezzed? %) (:title %) unrezzed-upgrade)
                           (get-root-content state)))
     :effect (req (cond
                    (= target unrezzed-upgrade)
                    ;; accessing an unrezzed upgrade
                    (let [from-root (get-root-content state)
                          unrezzed (filter #(and (= (last (:zone %)) :content) (not (:rezzed %)))
                                           from-root)]
                      (if (= 1 (count unrezzed))
                        ;; only one unrezzed upgrade; access it and continue
                        (when-completed (handle-access state side unrezzed)
                                        (if (or (pos? amount) (< 1 (count from-root)))
                                          (continue-ability
                                            state side
                                            (access-helper-hq-or-rd state chosen-zone label amount select-fn title-fn
                                                                    (conj already-accessed (first unrezzed)))
                                            card nil)
                                          (effect-completed state side eid)))
                        ;; more than one unrezzed upgrade. allow user to select with mouse.
                        (continue-ability
                          state side
                          {:delayed-completion true
                           :prompt (str "Choose an upgrade in " server-name " to access.")
                           :choices {:req #(and (= (second (:zone %)) chosen-zone)
                                                (complement already-accessed))}
                           :effect (req (when-completed (handle-access state side [target])
                                                        (continue-ability
                                                          state side
                                                          (access-helper-hq-or-rd state chosen-zone label amount select-fn title-fn
                                                                                  (conj already-accessed target))
                                                          card nil)))}
                          card nil)))
                    ;; accessing a card in deck
                    (= target card-from)
                    (let [accessed (select-fn already-accessed)]
                      (when-completed (handle-access state side (make-eid state) [accessed]
                                                     (title-fn accessed))

                                      (let [from-root (get-root-content state)]
                                        (if (or (< 1 amount) (not-empty from-root))
                                          (continue-ability
                                            state side
                                            (access-helper-hq-or-rd state chosen-zone label (dec amount) select-fn title-fn
                                                                    (if (-> @state :run :shuffled-during-access chosen-zone)
                                                                      ;; if the zone was shuffled because of the access,
                                                                      ;; the runner "starts over" excepting any upgrades that were accessed
                                                                      (do (swap! state update-in [:run :shuffled-during-access] dissoc chosen-zone)
                                                                          (set (filter #(= :servers (first (:zone %)))
                                                                                       already-accessed)))
                                                                      (conj already-accessed accessed)))
                                            card nil)
                                          (effect-completed state side eid)))))
                    ;; accessing a rezzed upgrade
                    :else
                    (let [accessed (some #(when (= (:title %) target) %) (get-root-content state))]
                      (when-completed (handle-access state side [accessed])
                                      (if (or (pos? amount) (< 1 (count (get-root-content state))))
                                        (continue-ability
                                          state side
                                          (access-helper-hq-or-rd state chosen-zone label amount select-fn title-fn
                                                                  (conj already-accessed accessed))
                                          card nil)
                                        (effect-completed state side eid))))))}))

(defmethod choose-access :rd [cards server]
  {:delayed-completion true
   :effect (req (if (pos? (count cards))
                  (if (= 1 (count cards))
                    (handle-access state side eid cards "an unseen card")
                    (let [from-rd (access-count state side :rd-access)]
                      (continue-ability state side (access-helper-hq-or-rd
                                                     state :rd "deck" from-rd
                                                     ;; access the first card in deck that has not been accessed.
                                                     (fn [already-accessed] (first (drop-while already-accessed
                                                                                               (-> @state :corp :deck))))
                                                     (fn [_] "an unseen card")
                                                     #{})
                                        card nil)))
                  (effect-completed state side eid)))})

(defmethod choose-access :hq [cards server]
  {:delayed-completion true
   :effect (req (if (pos? (count cards))
                  (if (and (= 1 (count cards)) (not (any-flag-fn? state :runner :slow-hq-access true)))
                    (handle-access state side eid cards)
                    (let [from-hq (min (access-count state side :hq-access)
                                       (-> @state :corp :hand count))
                          ; Handle root only access - no cards to access in hand
                          from-hq (if (some #(= '[:hand] (:zone %)) cards) from-hq 0)]
                      (continue-ability state side (access-helper-hq-or-rd
                                                     state :hq "hand" from-hq
                                                     (fn [already-accessed] (some #(when-not (already-accessed %) %)
                                                                                  (shuffle (-> @state :corp :hand))))
                                                     (fn [card] (:title card))
                                                     #{})
                                        card nil)))
                  (effect-completed state side eid)))})


(defn access-helper-hq
  "This is a helper for cards to invoke HQ access without knowing how to use the full access method. See Dedicated Neural Net."
  [state from-hq already-accessed]
  (access-helper-hq-or-rd state :hq "hand" from-hq
                          (fn [already-accessed] (some #(when-not (already-accessed %) %)
                                                       (shuffle (-> @state :corp :hand))))
                          :title
                          already-accessed))


(defn- get-archives-accessible [state]
  ;; only include agendas and cards with an :access ability whose :req is true
  ;; (or don't have a :req, or have an :optional with no :req, or :optional with a true :req.)
  (filter #(let [cdef (card-def %)]
             ;; must also be :seen
             (and (:seen %)
                  (or (is-type? % "Agenda")
                      (should-trigger? state :corp % nil (:access cdef)))))
          (get-in @state [:corp :discard])))

(defn- get-archives-inactive [state]
  ;; get faceup cards with no access interaction
  (filter #(let [cdef (card-def %)]
             (and (:seen %)
                  (not (or (is-type? % "Agenda")
                           (should-trigger? state :corp % nil (:access cdef))))))
          (get-in @state [:corp :discard])))

(defn access-helper-archives [state amount already-accessed]
  (let [root-content (fn [already-accessed] (remove already-accessed (-> @state :corp :servers :archives :content)))
        faceup-accessible (fn [already-accessed] (remove already-accessed (get-archives-accessible state)))
        facedown-cards (fn [already-accessed] (filter #(and (not (:seen %))
                                                            (not (already-accessed %)))
                                                      (-> @state :corp :discard)))

        next-access (fn [state side eid already-accessed card]
                      (continue-ability state side (access-helper-archives state (dec amount) already-accessed)
                                        card nil))

        must-continue? (fn [already-accessed]
                         (and (< 1 amount)
                              (pos? (+ (count (root-content already-accessed))
                                       (count (faceup-accessible already-accessed))
                                       (count (facedown-cards already-accessed))))))]
    {:delayed-completion true
     :prompt "Select a card to access. You must access all cards."
     :choices (concat (when (<= amount (count (filter (complement already-accessed) (get-archives-inactive state))))
                        [(str "Access " amount " inactive cards")])
                      (map :title (faceup-accessible already-accessed))
                      (map #(if (rezzed? %) (:title %) "Unrezzed upgrade in Archives") (root-content already-accessed))
                      (map (fn [_] (str "Facedown card in Archives")) (facedown-cards already-accessed)))
     :effect (req (cond
                    (.endsWith target "inactive cards")
                    ;; Interaction with Bacterial Programming. If we have X accesses remaining and <= X inactive cards
                    ;; in Archives, we don't have to access the remaining active cards.  This only happens if you choose
                    ;; to access at least one of the facedown cards added to Archives by Bacterial Programming.
                    (do (system-msg state side "accesses the remaining inactive cards in Archives")
                        (effect-completed state side eid))

                    (= target "Facedown card in Archives")
                    ;; accessing a card that was added to archives because of the effect of another card
                    (let [accessed (first (shuffle (facedown-cards already-accessed)))
                          already-accessed (conj already-accessed accessed)]
                      (when-completed (handle-access state side [accessed])
                                      (if (must-continue? already-accessed)
                                        (next-access state side eid already-accessed card)
                                        (effect-completed state side eid))))

                    (= target "Unrezzed upgrade in Archives")
                    ;; accessing an unrezzed upgrade
                    (let [unrezzed (filter #(and (= (last (:zone %)) :content) (not (:rezzed %)))
                                           (root-content already-accessed))]
                      (if (= 1 (count unrezzed))
                        ;; only one unrezzed upgrade; access it and continue
                        (let [already-accessed (conj already-accessed (first unrezzed))]
                          (when-completed (handle-access state side unrezzed)
                                          (if (must-continue? already-accessed)
                                            (next-access state side eid already-accessed card)
                                            (effect-completed state side eid))))
                        ;; more than one unrezzed upgrade. allow user to select with mouse.
                        (continue-ability
                          state side
                          {:delayed-completion true
                           :prompt "Choose an upgrade in Archives to access."
                           :choices {:req #(and (= (second (:zone %)) :archives)
                                                (not (already-accessed %)))}
                           :effect (req (let [already-accessed (conj already-accessed target)]
                                          (when-completed (handle-access state side [target])
                                                          (if (must-continue? already-accessed)
                                                            (next-access state side eid already-accessed card)
                                                            (effect-completed state side eid)))))}
                          card nil)))

                    :else
                    ;; accessing a rezzed upgrade, or a card in archives
                    (let [accessed (some #(when (= (:title %) target) %)
                                         (concat (faceup-accessible already-accessed) (root-content already-accessed)))
                          already-accessed (conj already-accessed accessed)]
                      (when-completed (handle-access state side [accessed])
                                      (if (must-continue? already-accessed)
                                        (next-access state side eid already-accessed card)
                                        (effect-completed state side eid))))))}))

(defmethod choose-access :archives [cards server]
  {:delayed-completion true
   :effect (req (let [cards (concat (get-archives-accessible state) (-> @state :corp :servers :archives :content))
                      archives-count (+ (count (-> @state :corp :discard)) (count (-> @state :corp :servers :archives :content)))]
                  (if (not-empty cards)
                    (if (= 1 archives-count)
                      (handle-access state side eid cards)
                      (continue-ability state side (access-helper-archives state archives-count #{}) card nil))
                    (effect-completed state side eid))))})

(defn get-all-hosted [hosts]
  (let [hosted-cards (mapcat :hosted hosts)]
    (if (empty? hosted-cards)
      hosted-cards
      (concat hosted-cards (get-all-hosted hosted-cards)))))


(defmulti cards-to-access
  "Gets the list of cards to access for the server"
  (fn [state side server] (get-server-type (first server))))

(defmethod cards-to-access :hq [state side server]
  (concat (take (access-count state side :hq-access) (shuffle (get-in @state [:corp :hand])))
          (get-in @state [:corp :servers :hq :content])))

(defmethod cards-to-access :rd [state side server]
  (concat (take (access-count state side :rd-access) (get-in @state [:corp :deck]))
          (get-in @state [:corp :servers :rd :content])))

(defmethod cards-to-access :archives [state side server]
  (swap! state update-in [:corp :discard] #(map (fn [c] (assoc c :seen true)) %))
  (concat (get-in @state [:corp :discard]) (get-in @state [:corp :servers :archives :content])))

(defmethod cards-to-access :remote [state side server]
  (let [contents (get-in @state [:corp :servers (first server) :content])]
    (filter (partial can-access-loud state side) (concat contents (get-all-hosted contents)))))

(defn do-access
  "Starts the access routines for the run's server."
  ([state side eid server] (do-access state side eid server nil))
  ([state side eid server {:keys [hq-root-only] :as args}]
   (when-completed (trigger-event-sync state side :pre-access (first server))
                   (do (let [cards (cards-to-access state side server)
                             cards (if hq-root-only (remove #(= '[:hand] (:zone %)) cards) cards)
                             n (count cards)]
                         ;; Cannot use `zero?` as it does not deal with `nil` nicely (throws exception)
                         (if (or (safe-zero? (get-in @state [:run :max-access]))
                                 (empty? cards))
                           (system-msg state side "accessed no cards during the run")
                           (do (when (:run @state)
                                 (swap! state assoc-in [:run :did-access] true))
                               (swap! state assoc-in [:runner :register :accessed-cards] true)
                               (when-completed (resolve-ability state side (choose-access cards server) nil nil)
                                               (effect-completed state side eid nil))
                               (swap! state update-in [:run :cards-accessed] (fnil #(+ % n) 0)))))
                       (handle-end-run state side)))))

(defn replace-access
  "Replaces the standard access routine with the :replace-access effect of the card"
  [state side ability card]
  (when-completed (resolve-ability state side ability card nil)
                  (run-cleanup state side)))

;;;; OLDER ACCESS ROUTINES. DEPRECATED.


;;; Ending runs.
(defn register-successful-run
  ([state side server] (register-successful-run state side (make-eid state) server))
  ([state side eid server]
   (swap! state update-in [:runner :register :successful-run] #(conj % (first server)))
   (swap! state assoc-in [:run :successful] true)
   (when-completed (trigger-event-simult state side :pre-successful-run nil (first server))
                   (when-completed (trigger-event-simult state side :successful-run nil (first (get-in @state [:run :server])))
                                   (when-completed (trigger-event-simult state side :post-successful-run nil (first (get-in @state [:run :server])))
                                                   (effect-completed state side eid nil))))))

(defn- successful-run-trigger
  "The real 'successful run' trigger."
  [state side]
  (let [successful-run-effect (get-in @state [:run :run-effect :successful-run])]
    (when (and successful-run-effect (not (apply trigger-suppress state side :successful-run
                                                 (get-in @state [:run :run-effect :card]))))
      (resolve-ability state side successful-run-effect (:card successful-run-effect) nil)))
  (when-completed (register-successful-run state side (get-in @state [:run :server]))
                  (let [server (get-in @state [:run :server]) ; bind here as the server might have changed
                        run-effect (get-in @state [:run :run-effect])
                        r (:req run-effect)
                        card (:card run-effect)
                        replace-effect (:replace-access run-effect)]
                    (if (and replace-effect
                             (or (not r) (r state side (make-eid state) card [(first server)])))
                      (if (:mandatory replace-effect)
                        (replace-access state side replace-effect card)
                        (swap! state update-in [side :prompt]
                               (fn [p]
                                 (conj (vec p) {:msg "Use Run ability instead of accessing cards?"
                                                :choices ["Run ability" "Access"]
                                                :effect #(if (= % "Run ability")
                                                          (replace-access state side replace-effect card)
                                                          (when-completed (do-access state side server)
                                                                          (handle-end-run state side)))}))))
                      (when-completed (do-access state side server)
                                      (handle-end-run state side))))))

(defn successful-run
  "Run when a run has passed all ice and the runner decides to access. The corp may still get to act in 4.3."
  [state side args]
  (if (get-in @state [:run :corp-phase-43])
    ;; if corp requests phase 4.3, then we do NOT fire :successful-run yet, which does not happen until 4.4
    (do (swap! state dissoc :no-action)
        (system-msg state :corp "wants to act before the run is successful")
        (show-wait-prompt state :runner "Corp's actions")
        (show-prompt state :corp nil "Rez and take actions before Successful Run" ["Done"]
                     (fn [args-corp]
                       (clear-wait-prompt state :runner)
                       (if-not (:ended (:run @state))
                        (show-prompt state :runner nil "The run is now successful" ["Continue"]
                                     (fn [args-runner] (successful-run-trigger state :runner)))
                        (handle-end-run state side)))
                     {:priority -1}))
    (successful-run-trigger state side)))

(defn corp-phase-43
  "The corp indicates they want to take action after runner hits Successful Run, before access."
  [state side args]
  (swap! state assoc-in [:run :corp-phase-43] true)
  (swap! state assoc-in [:run :no-action] true)
  (system-msg state side "has no further action")
  (trigger-event state side :no-action))

(defn end-run
  "End this run, and set it as UNSUCCESSFUL"
  ([state side] (end-run state side (make-eid state)))
  ([state side eid]
   (let [run (:run @state)
         server (first (get-in @state [:run :server]))]
     (swap! state update-in [:runner :register :unsuccessful-run] #(conj % server))
     (swap! state assoc-in [:run :unsuccessful] true)
     (handle-end-run state side)
     (trigger-event-sync state side eid :unsuccessful-run run))))

(defn jack-out-prevent
  [state side]
  (swap! state update-in [:jack-out :jack-out-prevent] (fnil inc 0))
  (prevent-jack-out state side))

(defn- resolve-jack-out
  [state side eid]
  (end-run state side)
  (system-msg state side "jacks out")
  (trigger-event-sync state side (make-result eid true) :jack-out))

(defn jack-out
  "The runner decides to jack out."
  ([state side] (jack-out state side (make-eid state)))
  ([state side eid]
  (swap! state update-in [:jack-out] dissoc :jack-out-prevent)
  (when-completed (trigger-event-sync state side :pre-jack-out)
                  (let [prevent (get-in @state [:prevent :jack-out])]
                    (if (pos? (count prevent))
                      (do (system-msg state :corp "has the option to prevent the Runner from jacking out")
                          (show-wait-prompt state :runner "Corp to prevent the jack out" {:priority 10})
                          (show-prompt state :corp nil
                                       (str "Prevent the Runner from jacking out?") ["Done"]
                                       (fn [_]
                                         (clear-wait-prompt state :runner)
                                         (if-let [_ (get-in @state [:jack-out :jack-out-prevent])]
                                           (effect-completed state side (make-result eid false))
                                           (do (system-msg state :corp "will not prevent the Runner from jacking out")
                                               (resolve-jack-out state side eid))))
                                       {:priority 10}))
                      (do (resolve-jack-out state side eid)
                          (effect-completed state side (make-result eid false))))))))

(defn- trigger-run-end-events
  [state side eid run]
  (cond
    ;; Successful
    (:successful run)
    (do
      (play-sfx state side "run-successful")
      (trigger-event-simult state side eid :successful-run-ends nil run))
    ;; Unsuccessful
    (:unsuccessful run)
    (do
      (play-sfx state side "run-unsuccessful")
      (trigger-event-sync state side eid :unsuccessful-run-ends run))
    ;; Neither
    :else
    (effect-completed state side eid)))

(defn run-cleanup
  "Trigger appropriate events for the ending of a run."
  [state side]
  (let [run (:run @state)
        server (:server run)
        eid (:eid run)]
    (swap! state assoc-in [:run :ending] true)
    (trigger-event state side :run-ends (first server))
    (doseq [p (filter #(has-subtype? % "Icebreaker") (all-active-installed state :runner))]
      (update! state side (update-in (get-card state p) [:pump] dissoc :all-run))
      (update! state side (update-in (get-card state p) [:pump] dissoc :encounter ))
      (update-breaker-strength state side p))
    (let [run-effect (get-in @state [:run :run-effect])]
      (when-let [end-run-effect (:end-run run-effect)]
        (resolve-ability state side end-run-effect (:card run-effect) [(first server)])))
    (swap! state update-in [:runner :credit] - (get-in @state [:runner :run-credit]))
    (swap! state assoc-in [:runner :run-credit] 0)
    (swap! state assoc :run nil)
    (update-all-ice state side)
    (swap! state dissoc :access)
    (clear-run-register! state)
    (trigger-run-end-events state side eid run)))

(defn handle-end-run
  "Initiate run resolution."
  [state side]
  (if-not (and (empty? (get-in @state [:runner :prompt])) (empty? (get-in @state [:corp :prompt])))
    (swap! state assoc-in [:run :ended] true)
    (run-cleanup state side)))

(defn close-access-prompt
  "Closes a 'You accessed _' prompt through a non-standard card effect like Imp."
  [state side]
  (let [prompt (-> @state side :prompt first)
        eid (:eid prompt)]
    (swap! state update-in [side :prompt] rest)
    (effect-completed state side eid nil)
    (when-let [run (:run @state)]
      (when (and (:ended run) (empty? (get-in @state [:runner :prompt])) )
        (handle-end-run state :runner)))))

(defn get-run-ices
  [state]
  (get-in @state (concat [:corp :servers] (:server (:run @state)) [:ices])))
