/* @metadata:begin
{"version":1,"rules":[{"id":"4304f500-7109-4112-a33c-8e83a0ede4b5","name":"DMARC","enabled":true,"matchType":"all","conditions":[{"field":"from","comparator":"contains","value":"dmarc@example.org"}],"actions":[{"type":"move","value":"Deleted Items"},{"type":"mark_read"}],"stopProcessing":false}]}
@metadata:end */

require ["fileinto", "imap4flags"];

# Rule: DMARC
if header :contains "From" "dmarc@example.org" {
    fileinto "Deleted Items";
    addflag "\\Seen";
}

# --- External rules (managed outside Bulwark) ---
# Deliverability probes from postmaster. See the operator's own notes.
# These prove each domain still accepts mail; acceptance is the whole signal, so
# there is nothing to read and nothing to keep.
if address :matches "to" "zz-canary@*" {
    discard;
    stop;
}
