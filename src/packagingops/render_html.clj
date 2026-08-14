(ns packagingops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack (`packagingops.operation` ->
  `packagingops.governor` -> `packagingops.phase` ->
  `packagingops.store`) through a scenario built on top of this repo's
  own `packagingops.sim` demo driver (`clojure -M:dev:run`, run and read
  BEFORE this file was written), then renders the resulting store.

  EVERY value on the page is real output:
    - the facility/contract/supplier directories are
      `packagingops.store/demo-data` read back through the `Store`
      protocol, not a hand-typed copy;
    - every coordination run is an actual `langgraph.graph/run*` through
      the compiled OperationActor graph;
    - every hold rule/detail string is the ContractPackagingGovernor's
      own violation map, verbatim;
    - the committed coordination log and the audit ledger are read out
      of the store after the run.

  The only hand-written table is the `Governor & phase contract` section,
  which documents fixed, declared behaviour (`governor/allowed-ops`,
  `governor/always-escalate-ops`, `phase/phases`) and derives its rows
  from those vars at render time rather than restating them.

  Determinism: no timestamps anywhere in the page; map payloads are
  rendered key-sorted rather than relying on Clojure map ordering; the
  store is freshly seeded on every run. Two consecutive renders are
  byte-identical.

  Styling: self-contained inline CSS carrying ONLY the `jp-go-dds`
  (デジタル庁デザインシステム) primitives this page actually references,
  with their real values read from the local checkout
  `orgs/kotoba-lang/jp-go-digital-design-system`. The git dep is
  deliberately NOT added -- it would make an offline
  `clojure -M:dev:render-html` depend on dependency resolution this repo
  does not otherwise need, and the pinned `tokens/bridge-css` bridges the
  `--hig-*` contract rather than the token names used here, so wiring it
  naively would silently unstyle the page. See the `css` var for the
  primitive->page-semantic mapping and for why error tints come from the
  `-50` step rather than `--color-semantic-error-2`.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [packagingops.advisor :as advisor]
            [packagingops.governor :as governor]
            [packagingops.operation :as op]
            [packagingops.phase :as phase]
            [packagingops.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private qa-coordinator "packaging-qa-coordinator-1")

(defn- ctx
  "The injected actor context. `:phase` is the rollout phase gate."
  [phase-n]
  {:actor-id "coord-1" :actor-role :packaging-operations-coordinator :phase phase-n})

(defn- run-thread!
  "Executes ONE coordination request as one supervised actor run, and --
  when the actor interrupts for human sign-off and the scenario supplies
  an `:approval` -- resumes it with that human decision. Returns the real
  graph results, untouched."
  [{:keys [actor tid label request context approval]}]
  (let [r1 (g/run* actor {:request request :context context} {:thread-id tid})
        r2 (when approval
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))]
    {:tid tid :label label :request request :context context
     :approval approval :first r1 :resume r2}))

(defn- direct-actuation-advisor
  "An advisor that tries to claim a DIRECT actuation by setting
  `:effect :commit` instead of `:propose` -- exercises the governor's
  `effect-not-propose` HARD check end-to-end. Same hook the repo's own
  `packagingops.sim` uses."
  []
  (reify advisor/Advisor
    (-advise [_ _ req] (assoc (advisor/infer nil req) :effect :commit))))

(defn- off-allowlist-advisor
  "An advisor that drifts OUTSIDE the closed four-op allowlist by
  proposing an op it was never authorized to propose. Exercises the
  governor's `op-not-allowed` HARD check (the same failure mode as a
  scope excursion, folded into `scope-exclusion-violations`)."
  []
  (reify advisor/Advisor
    (-advise [_ _ req]
      (assoc (advisor/infer nil req) :op :issue-package-integrity-clearance))))

(defn run-demo!
  "Runs a freshly seeded store through the full disposition space of this
  actor and returns `{:db store :threads [..]}`.

  Happy path (full lifecycle, real seeded ids):
    t01 phase 1 production-record log -> phase gate escalates -> human
        approves -> commits (this is the approver-attribution probe)
    t02 phase 3 production-record log -> governor-clean, auto-commits
    t03 phase 3 packaging-line schedule (contract-2) -> auto-commits
    t04 phase 3 low-cost supply order, verified supplier -> auto-commits
    t05 phase 3 HIGH-cost supply order -> always escalates -> approved
    t06 phase 3 quality-concern flag -> always escalates -> approved
    t07 phase 3 quality-concern flag -> human REJECTS -> hold

  HARD holds (un-overridable, never reach a human):
    t08 :facility-unverified  -- facility absent from the directory
    t09 :facility-unverified  -- facility registered but NOT verified
    t10 :contract-unverified  -- client contract not verified
    t11 :supplier-unverified  -- named supplier not verified
    t12 :supplier-unverified  -- no :supplier-id named at all
    t13 :effect-not-propose   -- advisor claims direct actuation
    t14 :scope-excluded       -- advisor drifts into clearance/sign-off
    t15 :op-not-allowed       -- advisor proposes an op off the allowlist

  Phase gate (governor-clean, blocked by rollout phase):
    t16 :phase-disabled       -- supply order attempted at phase 1"
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        direct  (op/build db {:advisor (direct-actuation-advisor)})
        offlist (op/build db {:advisor (off-allowlist-advisor)})
        approve {:status :approved :by qa-coordinator}
        reject  {:status :rejected :by qa-coordinator}
        specs
        [{:actor actor :tid "t01" :context (ctx 1) :approval approve
          :label "生産記録ログ (phase 1 -- 常に人間承認)"
          :request {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:batch-id "b-2001" :units-packaged 5000 :rejected-units 12}}}

         {:actor actor :tid "t02" :context (ctx 3)
          :label "生産記録ログ (phase 3 -- 自動コミット)"
          :request {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:batch-id "b-2002" :units-packaged 4800 :rejected-units 4}}}

         {:actor actor :tid "t03" :context (ctx 3)
          :label "梱包ライン/人員配置予定 (phase 3 -- 自動コミット)"
          :request {:op :schedule-production-operation :facility-id "facility-2" :contract-id "contract-2"
                    :patch {:line "blister-line-2" :date "2026-07-20" :window "06:00-14:00"}}}

         {:actor actor :tid "t04" :context (ctx 3)
          :label "資材発注調整 -- 低額・検証済み仕入先 (自動コミット)"
          :request {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:item "tamper-evident cap seals restock" :quantity 20000
                            :estimated-cost 650.0 :supplier-id "supplier-1"}}}

         {:actor actor :tid "t05" :context (ctx 3) :approval approve
          :label "資材発注調整 -- 高額 (phase 3 でも常に人間承認)"
          :request {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:item "blister-pack tooling changeover kit" :quantity 4
                            :estimated-cost 8500.0 :supplier-id "supplier-1"}}}

         {:actor actor :tid "t06" :context (ctx 3) :approval approve
          :label "品質懸念フラグ (どの phase でも常に人間承認)"
          :request {:op :flag-quality-concern :facility-id "facility-2" :contract-id "contract-2"
                    :patch {:concern "tamper-evident seal appears compromised on a sample from batch b-2002, cap torque reading below threshold"
                            :confidence 0.91}}}

         {:actor actor :tid "t07" :context (ctx 3) :approval reject
          :label "品質懸念フラグ -- 人間が却下"
          :request {:op :flag-quality-concern :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:concern "label lot code mismatch observed on retained sample from batch b-2001"
                            :confidence 0.88}}}

         {:actor actor :tid "t08" :context (ctx 3)
          :label "未登録の施設 -> HARD hold"
          :request {:op :log-production-record :facility-id "facility-99" :contract-id "contract-1"
                    :patch {:units-packaged 0}}}

         {:actor actor :tid "t09" :context (ctx 3)
          :label "登録済みだが未検証の施設 -> HARD hold"
          :request {:op :log-production-record :facility-id "facility-3" :contract-id "contract-1"
                    :patch {:units-packaged 10}}}

         {:actor actor :tid "t10" :context (ctx 3)
          :label "未検証の顧客契約 -> HARD hold"
          :request {:op :log-production-record :facility-id "facility-1" :contract-id "contract-3"
                    :patch {:units-packaged 10}}}

         {:actor actor :tid "t11" :context (ctx 3)
          :label "未検証の資材仕入先 -> HARD hold"
          :request {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:item "import carton stock" :quantity 5000
                            :estimated-cost 300.0 :supplier-id "supplier-2"}}}

         {:actor actor :tid "t12" :context (ctx 3)
          :label "仕入先 ID を名指ししない発注調整 -> HARD hold"
          :request {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:item "shrink sleeve film" :quantity 12000 :estimated-cost 900.0}}}

         {:actor direct :tid "t13" :context (ctx 3)
          :label "advisor が直接作動を主張 (:effect :commit) -> HARD hold"
          :request {:op :schedule-production-operation :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:line "gift-wrap-line-1" :date "2026-07-22"}}}

         {:actor actor :tid "t14" :context (ctx 3)
          :label "advisor が完全性確認/表示適合確定の領域へ逸脱 -> HARD hold (永久)"
          :request {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                    :out-of-scope? true :patch {}}}

         {:actor offlist :tid "t15" :context (ctx 3)
          :label "advisor が allowlist 外の操作を提案 -> HARD hold"
          :request {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:batch-id "b-2003" :units-packaged 100}}}

         {:actor actor :tid "t16" :context (ctx 1)
          :label "phase 1 では未開放の発注調整 -> phase gate が hold"
          :request {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                    :patch {:item "carton stock top-up" :quantity 3000
                            :estimated-cost 400.0 :supplier-id "supplier-1"}}}]]
    {:db db :threads (mapv run-thread! specs)}))

;; ----------------------------- derived views -----------------------------

(defn- final-state [{:keys [first resume]}] (:state (or resume first)))

(defn- thread-audit
  "Every audit fact the graph produced for one thread, first run then
  resume."
  [{:keys [first resume]}]
  (into (vec (get-in first [:state :audit]))
        (when resume
          ;; the resumed run replays the thread's accumulated audit
          (drop (count (get-in first [:state :audit]))
                (get-in resume [:state :audit])))))

(defn- thread-disposition [t] (:disposition (final-state t)))

(defn- thread-hold-basis
  "The governor rules (or the phase reason) that held this thread, read
  from the actual audit facts -- never re-derived by this renderer."
  [t]
  (let [facts (filter #(#{:governor-hold :approval-rejected} (:t %)) (thread-audit t))]
    (vec (distinct (mapcat (fn [f]
                             (if (seq (:basis f))
                               (map name (:basis f))
                               (when-let [r (:phase-reason f)] [(name r)])))
                           facts)))))

(defn- approvals-granted
  "Real `:approval-granted` facts observed in the graph state after a
  human resumed the actor. This is the ground truth the store is then
  measured against."
  [threads]
  (vec (for [t threads
             f (thread-audit t)
             :when (= :approval-granted (:t f))]
         {:tid (:tid t) :op (:op f) :by (:by f)
          :facility-id (:facility-id f) :contract-id (:contract-id f)})))

(defn- approver-in
  "Scans an arbitrary committed record for ANY key naming an approver,
  at any depth. Deliberately NOT hard-coded to `[:payload :approved-by]`
  -- if the actor or store ever changes where it keeps the approver,
  this scan (and therefore the page's disclosure) follows it."
  [record]
  (let [found (atom nil)]
    (letfn [(walk [x]
              (when (map? x)
                (doseq [[k v] x]
                  (when (and (keyword? k)
                             (str/includes? (name k) "approv")
                             (some? v)
                             (not (map? v)))
                    (reset! found {:key k :value v}))
                  (walk v))))]
      (walk record))
    @found))

(defn- attribution
  "MEASURES this repo's store, rather than assuming a fleet-wide defect.
  Compares the approvals the graph actually granted against what the
  committed records and the audit ledger retained.

  `:value-blind-count` measures a SECOND, independent question that the
  headline status cannot express: even when a record keeps the approver,
  is that approver reachable from the record's `:value` -- the key a
  consumer would naturally read, and the only one `commit-record`
  guarantees is populated (`(or (:value proposal) {})`)? A record whose
  approver lives solely on a sibling key is retained but effectively
  invisible, which is a different failure from losing it outright."
  [db threads]
  (let [granted   (approvals-granted threads)
        records   (vec (store/coordination-log db))
        attributed (filterv approver-in records)
        value-blind (filterv #(and (approver-in %) (nil? (approver-in (:value %)))) records)
        ledger-approvals (filterv #(= :approval-granted (:t %)) (store/ledger db))]
    {:granted-count    (count granted)
     :record-count     (count records)
     :attributed-count (count attributed)
     :attributed-keys  (vec (distinct (map (comp str :key approver-in) attributed)))
     :value-blind-count (count value-blind)
     :ledger-approval-count (count ledger-approvals)
     :status (cond
               (zero? (count granted))          :no-approval-path
               (= (count attributed)
                  (count granted))              :record-retains-approver
               (zero? (count attributed))       :approver-lost-in-store
               :else                            :partial)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc
  "Escapes store/governor data on its way into the document. Applied
  EXACTLY ONCE, at the data boundary -- markup literals below are never
  passed through it, so no entity is ever escaped twice."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(declare fmt-val)

(defn- fmt-map
  "Key-sorted rendering of a payload map -- deterministic regardless of
  Clojure's array-map/hash-map switchover."
  [m]
  (str/join " · " (for [[k v] (sort-by (comp str first) m)]
                    (str (name k) "=" (fmt-val v)))))

(defn- fmt-val [v]
  (cond
    (nil? v)        "—"
    (map? v)        (fmt-map v)
    (keyword? v)    (str v)
    (string? v)     v
    (sequential? v) (str/join ", " (map fmt-val v))
    :else           (pr-str v)))

(defn- kw [v] (if v (str "<code>" (esc v) "</code>") "<span class=\"muted\">—</span>"))

(defn- pill [class label] (str "<span class=\"pill " class "\">" label "</span>"))

(defn- yes-no [b]
  (if b (pill "ok" "yes") (pill "bad" "NO")))

(defn- disposition-pill [d]
  (case d
    :commit   (pill "ok" "commit")
    :escalate (pill "warn" "escalate")
    :hold     (pill "bad" "HOLD")
    (pill "muted" "—")))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lede head-cells body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"lede\">" lede "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") head-cells)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" body-rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- sections -----------------------------

(defn- directory-section [title lede id-key label-key records]
  (section title lede
           ["ID" label-key "registered?" "verified?" "提案を進められるか"]
           (for [r records]
             (row (kw (id-key r))
                  (esc (get r (keyword (str/lower-case label-key))
                            (or (:name r) (:client r))))
                  (yes-no (:registered? r))
                  (yes-no (:verified? r))
                  (if (and (:registered? r) (:verified? r))
                    (pill "ok" "yes")
                    (pill "bad" "no · HARD hold"))))))

(defn- run-rows [threads]
  (for [t threads
        :let [st (final-state t)
              basis (thread-hold-basis t)
              appr (:approval t)]]
    (row (kw (:tid t))
         (esc (:label t))
         (kw (get-in t [:request :op]))
         (esc (str (get-in t [:request :facility-id]) " / " (get-in t [:request :contract-id])))
         (str (get-in t [:context :phase]))
         (esc (format "%.2f" (double (get-in st [:verdict :confidence] 0.0))))
         (cond
           (nil? appr) (pill "muted" "—")
           (= :approved (:status appr)) (pill "ok" (str "approved · " (esc (:by appr))))
           :else (pill "bad" (str "rejected · " (esc (:by appr)))))
         (disposition-pill (thread-disposition t))
         (if (seq basis)
           (str/join " " (map #(str "<code class=\"bad\">" (esc %) "</code>") basis))
           "<span class=\"muted\">—</span>"))))

(defn- governor-hard-holds
  "Ledger facts held by a HARD ContractPackagingGovernor violation.

  A phase-gate hold is written with the SAME `:t :governor-hold` tag but
  carries NO `:violations` -- it is a rollout decision that disappears
  the moment the phase advances, whereas every fact returned here is
  permanent and un-overridable by any human approval. Counting the two
  together would overstate how much of the governor this run actually
  exercised, so they are separated at the source rather than in prose."
  [db]
  (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) (store/ledger db)))

(defn- phase-gate-holds
  "Ledger holds produced by the rollout phase gate on a governor-CLEAN
  proposal (`:violations` empty, `:phase-reason` set)."
  [db]
  (filter #(and (= :governor-hold (:t %)) (empty? (:violations %))) (store/ledger db)))

(defn- hold-rule-rows
  "Groups the run's holds by the rule that fired, with the governor's OWN
  detail string for each, labelling whether each rule is a permanent
  HARD block or a phase-gate decision."
  [db]
  (let [holds (filter #(= :governor-hold (:t %)) (store/ledger db))
        by-rule (reduce (fn [acc f]
                          (if (seq (:violations f))
                            (reduce (fn [a v]
                                      (update a (name (:rule v))
                                              (fnil conj [])
                                              {:kind :hard
                                               :detail (:detail v) :op (:op f)
                                               :facility-id (:facility-id f)
                                               :contract-id (:contract-id f)}))
                                    acc (:violations f))
                            (update acc (name (or (:phase-reason f) :unclassified))
                                    (fnil conj [])
                                    {:kind :phase
                                     :detail (str "phase " (:phase f) " ("
                                                  (:label (get phase/phases (:phase f)))
                                                  ") はこの操作の書き込みをまだ開放していない")
                                     :op (:op f)
                                     :facility-id (:facility-id f)
                                     :contract-id (:contract-id f)})))
                        (sorted-map) holds)]
    (for [[rule occurrences] by-rule]
      (row (str "<code class=\"bad\">" (esc rule) "</code>")
           (if (= :hard (:kind (first occurrences)))
             (pill "bad" "HARD · 上書き不可")
             (pill "warn" "phase gate · phase 前進で解除"))
           (str (count occurrences))
           (esc (str/join ", " (distinct (map (comp str :op) occurrences))))
           (esc (str/join " / " (distinct (map :detail occurrences))))))))

(defn- ledger-rows [db]
  (for [f (store/ledger db)]
    (row (case (:t f)
           :committed (pill "ok" "committed")
           :governor-hold (pill "bad" "governor-hold")
           :approval-rejected (pill "bad" "approval-rejected")
           (pill "muted" (esc (name (:t f)))))
         (kw (:op f))
         (esc (:actor f))
         (esc (str (:facility-id f) " / " (:contract-id f)))
         (if (seq (:basis f))
           (esc (str/join ", " (map #(if (keyword? %) (name %) (str %)) (:basis f))))
           "<span class=\"muted\">—</span>")
         (esc (or (:summary f) "")))))

(defn- commit-rows
  "One row per committed SSoT record.

  The approver cell is read from the run's MEASURED attribution status
  rather than inferred from the absence of a key. An empty approver cell
  may mean two entirely different things -- nobody ever approved this
  record, or somebody did and the store failed to keep it -- and only
  the measurement can tell them apart. So the confident label
  `承認なし (自動コミット)` is printed ONLY when this run demonstrated
  that every approval the graph granted survived into a record. When
  attribution is lossy or partial, the same empty cell is reported as
  unknowable instead, because in that run it genuinely is."
  [db att]
  (let [decidable? (contains? #{:record-retains-approver :no-approval-path} (:status att))]
    (for [r (store/coordination-log db)
          :let [a (approver-in r)]]
      (row (kw (:op r))
           (esc (str (:facility-id r) " / " (:contract-id r)))
           (esc (fmt-map (:value r)))
           (cond
             a (str (pill "ok" (esc (str (:value a)))) " <code>" (esc (:key a)) "</code>"
                    (when (nil? (approver-in (:value r)))
                      (str " <span class=\"muted\">(<code>:value</code> 側には無い)</span>")))
             decidable? "<span class=\"muted\">承認なし (自動コミット)</span>"
             :else (str (pill "warn" "判別不能")
                        " <span class=\"muted\">この実行では承認者の保持が不完全なため、"
                        "自動コミットだったのか承認者が失われたのかをレコードから区別できない</span>"))))))

(defn- contract-rows
  "Derived from the governor/phase vars themselves, so this table cannot
  drift away from the code it documents."
  []
  (let [auto3 (get-in phase/phases [3 :auto])]
    (for [o (sort-by str governor/allowed-ops)]
      (row (kw o)
           (if (contains? governor/always-escalate-ops o)
             (pill "warn" "常に人間承認")
             (if (contains? auto3 o)
               (pill "ok" "phase 3 で自動コミット可")
               (pill "warn" "人間承認")))
           (esc (str/join ", " (for [[n {:keys [label writes]}] (sort phase/phases)
                                     :when (contains? writes o)]
                                 (str n " " label))))
           (if (= :coordinate-supply-order o)
             (esc (str "estimated-cost > " governor/supply-cost-threshold " は常にエスカレート"))
             "<span class=\"muted\">—</span>")))))

(defn- attribution-section [att]
  (let [{:keys [status granted-count attributed-count record-count
                attributed-keys value-blind-count ledger-approval-count]} att]
    (str "  <section class=\"card\">\n"
         "    <h2>承認者の帰属 (この実行で実測)</h2>\n"
         "    <p class=\"lede\">この節は固定文ではない — レンダリング時に、コミット済みレコードを"
         "承認者キーについて実際に走査した結果から導出している。ストアの挙動が変われば、この文も変わる。</p>\n"
         "    <table>\n"
         "      <thead><tr><th>測定項目</th><th>実測値</th></tr></thead>\n"
         "      <tbody>\n"
         (str/join "\n"
           [(row "グラフが実際に付与した承認 (<code>:approval-granted</code>)" (str granted-count))
            (row "SSoT に書かれたコミット済みレコード" (str record-count))
            (row "承認者を保持しているレコード" (str attributed-count))
            (row "承認者が入っていたキー"
                 (if (seq attributed-keys)
                   (str/join ", " (map #(str "<code>" (esc %) "</code>") attributed-keys))
                   "<span class=\"muted\">なし</span>"))
            (row "うち <code>:value</code> からは辿れないレコード" (str value-blind-count))
            (row "監査台帳に残った <code>:approval-granted</code> ファクト" (str ledger-approval-count))])
         "\n      </tbody>\n"
         "    </table>\n"
         "    <p class=\"note "
         (if (= :record-retains-approver status) "note-ok" "note-bad") "\">"
         (case status
           :record-retains-approver
           (str "測定結果: このリポジトリのストアは<strong>承認者を保持している</strong>。"
                granted-count " 件の承認すべてについて、コミット済みレコードから「誰が承認したか」を答えられる"
                "(<code>commit-record!</code> がレコード全体を保持しており、"
                (str/join "/" attributed-keys) " がそのまま残る)。"
                (when (pos? value-blind-count)
                  (str " ただしその " value-blind-count
                       " 件すべてで、承認者はレコードの <code>:payload</code> にしか無い — "
                       "<code>:value</code> は同じ形をしていながら承認者を含まない。"
                       "<code>commit-record</code> は <code>:value</code> を "
                       "<code>(or (:value proposal) {})</code> で常に埋める一方、承認者は "
                       "<code>:request-approval</code> ノードが <code>:payload</code> 側にだけ "
                       "<code>assoc</code> するため、<code>:value</code> だけを読む利用者は"
                       "承認済みレコードを未承認と誤読する。上の表の承認者列がこの走査に依存しており、"
                       "<code>:value</code> を直接読んでいないのはそのため。"))
                (if (zero? ledger-approval-count)
                  (str " ただし追記専用の監査台帳には <code>:approval-granted</code> ファクトが 1 件も書かれていない — "
                       "承認の事実は <code>packagingops.operation</code> のグラフ状態には存在するが、"
                       "<code>:commit</code> ノードは <code>commit-fact</code> しか台帳に追記しないため、"
                       "台帳だけを読んでも承認者は分からない。答えはレコード側にのみ在る。")
                  " 監査台帳にも承認ファクトが残っている。"))

           :approver-lost-in-store
           (str "測定結果: <strong>承認者の帰属が失われている</strong>。グラフは " granted-count
                " 件の承認を付与したが、コミット済みレコードのどれにも承認者キーが無い — "
                "ストアから「誰が承認したか」を答えられない。"
                (if (pos? ledger-approval-count)
                  " 監査台帳側には承認ファクトが残っているので、答えは台帳にのみ在る。"
                  " 監査台帳にも承認ファクトが無いので、この実行からは承認者を復元できない。"))

           :partial
           (str "測定結果: <strong>部分的</strong>。" granted-count " 件の承認のうち "
                attributed-count " 件だけがコミット済みレコードに承認者を残している。")

           :no-approval-path
           "測定結果: この実行では承認が 1 件も付与されなかったため、承認者の帰属は測定できていない。")
         "</p>\n"
         "  </section>\n")))

;; ----------------------------- document -----------------------------

(def ^:private css "
/* --- jp-go-dds (デジタル庁デザインシステム) primitives, inlined ---------------
   ONLY the primitives this page actually references are copied here, with
   their real values read from the local checkout
   orgs/kotoba-lang/jp-go-digital-design-system/resources/jp_go_dds/dds.css.

   The git dep is deliberately NOT added: it would make an offline
   `clojure -M:dev:render-html` depend on dependency resolution this repo
   does not otherwise need, and the pinned `tokens/bridge-css` bridges the
   `--hig-*` contract, not the token names this console uses -- wiring it
   naively would silently unstyle the page.

   NOTE on the error ramp: `--color-semantic-error-1`/`-2` are red-800 and
   red-900 -- BOTH dark, not a strong/weak pair. Tint backgrounds therefore
   come from the `-50` step, never from `-2`. */
:root{
  --color-primitive-blue-900:#0017c1;
  --color-primitive-green-50:#e6f5ec;  --color-primitive-green-800:#197a4b;
  --color-primitive-orange-50:#ffeee2; --color-primitive-orange-800:#c74700;
  --color-primitive-red-50:#fdeeee;    --color-primitive-red-900:#ce0000;
  --color-neutral-white:#ffffff;
  --color-neutral-solid-gray-50:#f2f2f2;  --color-neutral-solid-gray-100:#e6e6e6;
  --color-neutral-solid-gray-200:#cccccc; --color-neutral-solid-gray-600:#666666;
  --color-neutral-solid-gray-900:#1a1a1a;

  /* page semantics mapped onto the primitives above */
  --fg:var(--color-neutral-solid-gray-900);
  --muted:var(--color-neutral-solid-gray-600);
  --line:var(--color-neutral-solid-gray-200);
  --line-soft:var(--color-neutral-solid-gray-100);
  --bg:var(--color-neutral-solid-gray-50);
  --card:var(--color-neutral-white);
  --code-bg:var(--color-neutral-solid-gray-50);
  --ok:var(--color-primitive-green-800);    --ok-bg:var(--color-primitive-green-50);
  --warn:var(--color-primitive-orange-800); --warn-bg:var(--color-primitive-orange-50);
  --bad:var(--color-primitive-red-900);     --bad-bg:var(--color-primitive-red-50);
  --accent:var(--color-primitive-blue-900)}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
     font:15px/1.65 -apple-system,BlinkMacSystemFont,'Hiragino Sans','Noto Sans JP',sans-serif}
header.bar{background:var(--accent);color:#fff;padding:22px 28px}
header.bar h1{margin:0 0 6px;font-size:20px;letter-spacing:.01em}
header.bar .sub{opacity:.85;font-size:13px}
main{max-width:1180px;margin:0 auto;padding:24px 20px 60px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;
      padding:20px 22px;margin:0 0 20px}
.card h2{margin:0 0 6px;font-size:16px}
.lede{margin:0 0 14px;color:var(--muted);font-size:13px}
table{width:100%;border-collapse:collapse;font-size:13px}
th{text-align:left;font-weight:600;color:var(--muted);border-bottom:2px solid var(--line);
   padding:7px 9px;white-space:nowrap}
td{border-bottom:1px solid var(--line-soft);padding:7px 9px;vertical-align:top}
tr:last-child td{border-bottom:none}
code{font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--code-bg);
     border-radius:4px;padding:1px 5px}
code.bad{background:var(--bad-bg);color:var(--bad)}
.pill{display:inline-block;border-radius:999px;padding:1px 9px;font-size:12px;font-weight:600;white-space:nowrap}
.pill.ok{background:var(--ok-bg);color:var(--ok)}
.pill.warn{background:var(--warn-bg);color:var(--warn)}
.pill.bad{background:var(--bad-bg);color:var(--bad)}
.pill.muted{background:var(--color-neutral-solid-gray-100);color:var(--muted)}
.muted{color:var(--muted)}
.note{margin:14px 0 0;padding:12px 14px;border-radius:8px;font-size:13px;line-height:1.7}
.note-ok{background:var(--ok-bg);border-left:4px solid var(--ok)}
.note-bad{background:var(--bad-bg);border-left:4px solid var(--bad)}
.stats{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 20px;padding:0;list-style:none}
.stats li{background:var(--card);border:1px solid var(--line);border-radius:10px;
          padding:12px 16px;min-width:132px}
.stats b{display:block;font-size:22px;line-height:1.2}
.stats span{font-size:12px;color:var(--muted)}
footer{max-width:1180px;margin:0 auto;padding:0 20px 40px;color:var(--muted);font-size:12px}
")

(defn- stats [db threads]
  (let [led (store/ledger db)]
    [["調整リクエスト" (count threads)]
     ["コミット" (count (filter #(= :committed (:t %)) led))]
     ["HARD hold (governor)" (count (governor-hard-holds db))]
     ["phase gate hold" (count (phase-gate-holds db))]
     ["人間の却下" (count (filter #(= :approval-rejected (:t %)) led))]
     ["SSoT レコード" (count (store/coordination-log db))]
     ["台帳ファクト" (count led)]]))

(defn render
  "Renders the operator console from a completed `run-demo!` result."
  [{:keys [db threads]}]
  (let [att (attribution db threads)]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
     "<title>cloud-itonami-isic-8292 · packaging operations コンソール</title>\n"
     "<style>" css "</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>ISIC 8292 包装業 (Packaging activities) — 運用コンソール</h1>\n"
     "  <div class=\"sub\">read-only sample · 調整専用 (coordination only) · "
     "包装完全性の安全確認と食品/医薬品表示の適合確定は永久にスコープ外</div>\n"
     "</header>\n<main>\n"
     "  <ul class=\"stats\">\n"
     (str/join "\n" (for [[label n] (stats db threads)]
                      (str "    <li><b>" n "</b><span>" label "</span></li>")))
     "\n  </ul>\n"

     (section "調整リクエストの実行結果"
              (str "1 リクエスト = 1 回の監督付きアクター実行 (intake → advise → govern → decide → "
                   "commit | hold | approval)。下の各行は <code>langgraph.graph/run*</code> の実行結果そのもので、"
                   "confidence は PackagingOpsAdvisor が返した値、disposition は ContractPackagingGovernor と "
                   "rollout phase gate が決めた値。")
              ["thread" "シナリオ" "op" "施設 / 契約" "phase" "confidence" "人間の判断" "disposition" "hold の根拠"]
              (run-rows threads))

     (section "hold の内訳 (この実行で実際に発火した規則)"
              (str "HARD hold は ContractPackagingGovernor の永久ブロックで、人間承認でも上書きできない。"
                   "phase gate hold は governor が clean と判定した提案を rollout phase が止めたもので、"
                   "phase を前進させれば解除される — 台帳上はどちらも <code>:governor-hold</code> だが、"
                   "<code>:violations</code> の有無で区別している。detail 列は governor 自身が返した文字列。")
              ["規則" "種別" "件数" "op" "governor の detail"]
              (hold-rule-rows db))

     (directory-section "包装施設ディレクトリ"
                        (str "<code>packagingops.store/demo-data</code> を <code>Store</code> プロトコル経由で"
                             "読み戻したもの。governor は提案の自己申告ではなくこのレコードから "
                             "<code>:registered?</code>/<code>:verified?</code> を再導出する。")
                        :facility-id "name" (store/all-facility-records db))

     (directory-section "顧客包装受託契約ディレクトリ"
                        (str "受託包装は必ず特定の顧客契約の下で行われる。契約が未検証なら、"
                             "施設がどれだけ健全でも全 op が HARD hold になる。")
                        :contract-id "client" (store/all-contract-records db))

     (directory-section "包装資材仕入先ディレクトリ"
                        (str "<code>:coordinate-supply-order</code> のみが仕入先を名指しする。"
                             "名指しが無い場合も未検証の場合と同じく HARD hold。")
                        :supplier-id "name" (store/all-supplier-records db))

     (section "コミット済み調整ログ (SSoT)"
              (str "<code>commit</code> ノードだけが書き込む。承認者列は、レコードを"
                   "承認者キーについて走査した実測結果 (下の節を参照)。")
              ["op" "施設 / 契約" "value" "承認者"]
              (commit-rows db att))

     (section "監査台帳 (追記専用)"
              "この実行が生成した全ての決定ファクト。commit も hold も却下も同じ台帳に残る。"
              ["ファクト" "op" "actor" "施設 / 契約" "basis" "summary"]
              (ledger-rows db))

     (section "Governor / phase 契約"
              (str "この表は <code>packagingops.governor/allowed-ops</code>、"
                   "<code>always-escalate-ops</code>、<code>packagingops.phase/phases</code> から"
                   "レンダリング時に導出している (手書きの複製ではない)。")
              ["op" "phase 3 での扱い" "書き込みが開放される phase" "追加のエスカレーション条件"]
              (contract-rows))

     (attribution-section att)

     "</main>\n<footer>\n"
     "  build-time generated by <code>packagingops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) — 実際の actor stack を実行した出力であり、"
     "手書きの表ではない。ページ内に時刻を持たないため、同じ seed に対して再実行しても byte 単位で同一。\n"
     "</footer>\n</body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        db     (:db result)
        led    (store/ledger db)
        hard   (governor-hard-holds db)
        phase-held (phase-gate-holds db)
        hard-rules (distinct (mapcat #(map (comp name :rule) (:violations %)) hard))]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; HARD hold has not exercised the ContractPackagingGovernor, and is
    ;; therefore not evidence that the governance layer works. Refuse to
    ;; write it.
    ;;
    ;; This deliberately counts only holds carrying `:violations`. A
    ;; phase-gate hold shares the `:t :governor-hold` tag but proves
    ;; nothing about the governor -- accepting one here would let a
    ;; scenario that never trips a single compliance rule still satisfy
    ;; the invariant, which is exactly the silence this check exists to
    ;; prevent.
    (when (empty? hard)
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO HARD governor holds "
                           "(:governor-hold facts carrying :violations), so the page "
                           "would not demonstrate a single enforced governor rule")
                      {:ledger-facts    (count led)
                       :hard-holds      0
                       :phase-gate-holds (count phase-held)})))
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             (str "(" (count led) " ledger facts, "
                  (count hard) " HARD governor holds over "
                  (count hard-rules) " distinct rules " (vec (sort hard-rules)) ", "
                  (count phase-held) " phase-gate holds, "
                  (count (store/coordination-log db)) " committed records)"))))
