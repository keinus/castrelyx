"""Versioned input/output contract. These IDs are plans, not Router API methods."""
ACTION_CRITERIA = {
    'OBSERVE': 'Maintain current policy; evidence fits expected activity.',
    'COLLECT_MORE': 'Insufficient or conflicting evidence; collect more before restricting.',
    'QOS_PROFILE': 'Temporarily rate limit the implicated flow; retain availability.',
    'ROUTE_PROFILE': 'Move affected service to an approved healthy inspection or alternate path.',
    'FLOW_DROP': 'Temporarily block only the implicated protocol, peer and port.',
    'HOST_ISOLATE': 'Temporarily isolate the confirmed compromised host.',
}
QUESTIONS = {
    'response': {'type':'choice','instructions':'Select the least disruptive effective response using the correlated evidence, approved activity and available controls.', 'criteria':ACTION_CRITERIA},
    'urgency': {'type':'score','instructions':'How urgently does the available evidence require a response?', 'criteria':['observe routinely','investigate soon','contain immediately']},
    'threat': {'type':'noul','instructions':'Does the available evidence support a security threat rather than approved or expected activity?'},
}
VERSION='security-decision-v1'

def gold(action, threat, urgency):
    return {
        'response': {'probabilities':{k:float(k==action) for k in ACTION_CRITERIA}},
        'urgency': {'probabilities':{str(k):float(k==urgency) for k in range(3)}},
        'threat': {'probabilities':{'false':float(not threat),'true':float(threat)}},
    }

def response_plan(action, confidence, context, entity_ref, min_confidence=0.0):
    """No shell commands, arbitrary destinations or network writes are supported."""
    reason=None
    if action not in ACTION_CRITERIA:reason='unknown_action'
    elif action not in context.get('capabilities',list(ACTION_CRITERIA)):reason='unsupported_action'
    elif confidence<min_confidence:reason='below_policy_threshold'
    elif action=='HOST_ISOLATE' and (context.get('protected_asset') or not context.get('identity_verified',False)):reason='identity_or_protected_asset'
    elif action=='ROUTE_PROFILE' and not context.get('alternate_path_healthy',False):reason='alternate_path_unavailable'
    if reason:action='COLLECT_MORE'
    return {'mode':'plan_only','action':action,'entity_ref':entity_ref,'ttl_seconds':300 if action in ('FLOW_DROP','HOST_ISOLATE','QOS_PROFILE','ROUTE_PROFILE') else 0,'profile_id':{'QOS_PROFILE':'approved_rate_limit','ROUTE_PROFILE':'approved_alternate_path'}.get(action),'deferred_reason':reason,'executed':False}

def decision_plan(answers, context, entity_ref, thresholds, quality=None):
    """A data-loss or contradictory decision must remain an explicit deferral."""
    action=answers['response'];threshold=thresholds.get(action['choice'],{}).get('min_confidence',1.01)
    plan=response_plan(action['choice'],action['answer_confidence'],context,entity_ref,threshold)
    reason=None
    if (quality or {}).get('buffer_dropped',0)>0:reason='incomplete_evidence_buffer_overflow'
    elif not answers['threat']['threat'] and plan['action'] in ('QOS_PROFILE','ROUTE_PROFILE','FLOW_DROP','HOST_ISOLATE'):reason='threat_response_disagreement'
    if reason:
        plan=response_plan('COLLECT_MORE',1.,context,entity_ref);plan['deferred_reason']=reason
    return plan
