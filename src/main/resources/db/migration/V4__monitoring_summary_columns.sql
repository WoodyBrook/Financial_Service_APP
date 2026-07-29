ALTER TABLE demo_scenario_state
    ADD COLUMN monitoring_customers_evaluated  INT,
    ADD COLUMN monitoring_suppressed_open_case INT,
    ADD COLUMN monitoring_cases_raised         INT;
