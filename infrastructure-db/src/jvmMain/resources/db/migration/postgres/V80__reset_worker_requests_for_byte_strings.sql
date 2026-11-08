-- Gateway protocol 21 replaces numeric payload arrays with byte strings.
-- A coordinated upgrade discards prior RPC requests/results instead of decoding the old representation.
TRUNCATE TABLE worker_requests;
