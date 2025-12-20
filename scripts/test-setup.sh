start locator
start server

create region --name=test-query-region-int --type=REPLICATE
create region --name=test-large-query-region-int --type=REPLICATE

create region --name=test-region --redundant-copies=2 --type=PARTITION_REDUNDANT
create region --name=test-empty-query-region-int --type=REPLICATE
create region --name=test-query-collect-region-fruit --type=REPLICATE_PERSISTENT_OVERFLOW
create region --name=test-param-query-region-int2 --type=REPLICATE