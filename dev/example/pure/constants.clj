(ns example.pure.constants)

(def incoming
  {
   1  :tick-price 
   2  :tick-size 
   3  :order-status 
   4  :err-msg
   5  :open-order 
   6  :acct-value
   7  :portfolio-value 
   8  :acct-update-time
   9  :next-valid-id
   10 :contract-data
   11 :execution-data 
   12 :market-depth 
   13 :market-depth-l2 
   14 :news-bulletins 
   15 :managed-accts
   16 :receive-fa 
   17 :historical-data 
   18 :bond-contract-data 
   19 :scanner-parameters 
   20 :scanner-data 
   21 :tick-option-computation 
   45 :tick-generic 
   46 :tick-string 
   47 :tick-efp 
   49 :current-time
   50 :real-time-bars 
   51 :fundamental-data 
   52 :contract-data-end 
   53 :open-order-end 
   54 :acct-download-end 
   55 :execution-data-end 
   56 :delta-neutral-validation 
   57 :tick-snapshot-end 
   58 :market-data-type 
   59 :commission-report 
   61 :position 
   62 :position-end 
   63 :account-summary 
   64 :account-summary-end 
   65 :verify-message-api 
   66 :verify-completed 
   67 :display-group-list 
   68 :display-group-updated 
   69 :verify-and-auth-message-api 
   70 :verify-and-auth-completed 
   71 :position-multi 
   72 :position-multi-end 
   73 :account-update-multi 
   74 :account-update-multi-end 
   75 :security-definition-option-parameter 
   76 :security-definition-option-parameter-end 
   77 :soft-dollar-tiers 
   78 :family-codes 
   79 :symbol-samples 
   80 :mkt-depth-exchanges 
   81 :tick-req-params 
   82 :smart-components 
   83 :news-article 
   84 :tick-news 
   85 :news-providers 
   86 :historical-news 
   87 :historical-news-end 
   88 :head-timestamp 
   89 :histogram-data 
   })

(def outgoing
  {:start-api          {:code 71 :version 2} ;;todo:connection options  
   :market-data        {:code 1 :version 11}
   :cancel-market-data {:code 2 :version 1}
   :current-time       {:code 49 :version 1}
   :contract-data      {:code 9 :version 7}
   :order-ids          {:code 8 :version 1}
   :managed-accounts   {:code 17 :version 1}
   :all-open-orders    {:code 16 :version 1}
   :open-orders        {:code 5 :version 1}
   :positions          {:code 61 :version 1}
   :cancel-positions   {:code 64 :version 1}
   :req-account-data   {:code 6 :version 2}
   

   ;;===========================================================================
   ;;NOT YET IMPLEMENTED
   ;;===========================================================================
   
   :place-order                   {:code 3}
   :cancel-order                  {:code 4}
   :req-executions                {:code 7}
   :req-mkt-depth                 {:code 10}
   :cancel-mkt-depth              {:code 11}
   :req-news-bulletins            {:code 12}
   :cancel-news-bulletins         {:code 13}
   :set-server-loglevel           {:code 14}
   :req-auto-open-orders          {:code 15}
   :req-fa                        {:code 18}
   :replace-fa                    {:code 19}
   :req-historical-data           {:code 20}
   :exercise-options              {:code 21}
   :req-scanner-subscription      {:code 22}
   :cancel-scanner-subscription   {:code 23}
   :req-scanner-parameters        {:code 24}
   :cancel-historical-data        {:code 25}
   :req-real-time-bars            {:code 50}
   :cancel-real-time-bars         {:code 51}
   :req-fundamental-data          {:code 52}
   :cancel-fundamental-data       {:code 53}
   :req-calc-implied-volat        {:code 54}
   :req-calc-option-price         {:code 55}
   :cancel-calc-implied-volat     {:code 56}
   :cancel-calc-option-price      {:code 57}
   :req-global-cancel             {:code 58}
   :req-market-data-type          {:code 59}
   :req-account-summary           {:code 62}
   :cancel-account-summary        {:code 63}
   :verify-request                {:code 65}
   :verify-message                {:code 66}
   :query-display-groups          {:code 67}
   :subscribe-to-group-events     {:code 68}
   :update-display-group          {:code 69}
   :unsubscribe-from-group-events {:code 70}
   :verify-and-auth-request       {:code 72}
   :verify-and-auth-message       {:code 73}
   :req-positions-multi           {:code 74}
   :cancel-positions-multi        {:code 75}
   :req-account-updates-multi     {:code 76}
   :cancel-account-updates-multi  {:code 77}
   :req-sec-def-opt-params        {:code 78}
   :req-soft-dollar-tiers         {:code 79}
   :req-family-codes              {:code 80}
   :req-matching-symbols          {:code 81}
   :req-mkt-depth-exchanges       {:code 82}
   :req-smart-components          {:code 83}
   :req-news-article              {:code 84}
   :req-news-providers            {:code 85}
   :req-historical-news           {:code 86}
   :req-head-timestamp            {:code 87}
   :req-histogram-data            {:code 88}
   :cancel-histogram-data         {:code 89}
   })


