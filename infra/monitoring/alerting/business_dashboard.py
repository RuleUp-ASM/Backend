"""사용자 행동 지표 대시보드 ruleup-<env>-business. 노션 「RuleUp 모니터링」 2절 — 서버 지표(ruleup-prod-ops)와 분리해서 본다.
지표 원천은 앱 BusinessMetrics(biz.*). 경보는 걸지 않는다 — 추세를 보는 화면이다. 재실행 안전.
사용: python business_dashboard.py [prod|stg]"""
import boto3, json, sys
R='ap-northeast-2'; ENV=sys.argv[1] if len(sys.argv)>1 else 'prod'
NS=f'RuleUp/App/{ENV}'
cw=boto3.client('cloudwatch',region_name=R)
widgets=[]; y=0
def text(md):
    global y; widgets.append({'type':'text','x':0,'y':y,'width':24,'height':1,'properties':{'markdown':md}}); y+=1
def row(*ws):
    global y; x=0
    for title,metrics,extra in ws:
        w=24//len(ws)
        p={'title':title,'region':R,'view':'timeSeries','stacked':False,'period':3600,'metrics':metrics}; p.update(extra or {})
        widgets.append({'type':'metric','x':x,'y':y,'width':w,'height':6,'properties':p}); x+=w
    y+=6
m=lambda name,dim,val,stat='Sum',**o:[NS,name,dim,val,{'stat':stat,**o}]

text(f'## 가입 · 로그인 ({ENV}, 1시간 단위)')
row(('가입',[m('biz.signup.count','result','success',label='가입 완료'),m('biz.signup.count','result','failure',label='가입 실패')],None),
    ('로그인',[m('biz.login.count','outcome','existing',label='기존 회원 로그인'),m('biz.login.count','outcome','new_user',label='신규(가입 진행)'),
              m('biz.login.count','outcome','failure',label='실패')],None),
    ('로그인 성공률(%)',[m('biz.login.count','outcome','existing',id='a',visible=False),m('biz.login.count','outcome','new_user',id='b',visible=False),
                     m('biz.login.count','outcome','failure',id='f',visible=False),
                     [{'expression':'IF(FILL(a,0)+FILL(b,0)+FILL(f,0)>0, 100*(FILL(a,0)+FILL(b,0))/(FILL(a,0)+FILL(b,0)+FILL(f,0)))','label':'로그인 성공률','id':'r'}]],
     {'yAxis':{'left':{'min':0,'max':100}}}))
text('## 인증')
row(('인증 시도',[m('biz.verification.attempt.count','method','sync',label='자동(sync)'),m('biz.verification.attempt.count','method','manual',label='수동')],None),
    # 오늘 FAILED 는 D+2 확정 전엔 늘 0 이라 오늘 값으로는 성공률을 못 낸다 — 오늘은 진행률, 성공률은 확정이 끝난 그제 귀속분.
    ('오늘 진행률(%) — 판정 행이 열린 멤버 중 성공',[m('biz.verification.today.value','status','success','Maximum',id='s',visible=False),
                        m('biz.verification.today.value','status','pending','Maximum',id='p',visible=False),
                        [{'expression':'IF(FILL(s,0)+FILL(p,0)>0, 100*FILL(s,0)/(FILL(s,0)+FILL(p,0)))','label':'오늘 진행률','id':'tr'}]],
     {'period':300,'yAxis':{'left':{'min':0,'max':100}}}),
    ('인증 성공률(%) — 확정된 그제 귀속분',[m('biz.verification.finalized.value','status','success','Maximum',id='fs',visible=False),
                        m('biz.verification.finalized.value','status','failed','Maximum',id='ff',visible=False),
                        [{'expression':'IF(FILL(fs,0)+FILL(ff,0)>0, 100*FILL(fs,0)/(FILL(fs,0)+FILL(ff,0)))','label':'인증 성공률','id':'sr'}]],
     {'period':300,'yAxis':{'left':{'min':0,'max':100}}}))
cw.put_dashboard(DashboardName=f'ruleup-{ENV}-business',DashboardBody=json.dumps({'widgets':widgets},ensure_ascii=False))
print('ok', len(widgets))
