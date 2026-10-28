"""사용자 행동 지표 대시보드 ruleup-<env>-business. 노션 「RuleUp 모니터링」 2절 — 서버 지표(ruleup-prod-ops)와 분리해서 본다.
지표 원천은 앱 BusinessMetrics·VerificationRateMetrics(biz.*). 경보는 걸지 않는다 — 추세와 자료용 화면이다. 재실행 안전.
prod 전용 — stg 의 가입·로그인 수는 QA 가 만든 값이라 추세로 볼 의미가 없어 stg 는 biz.* 를 내보내지도 않는다(CloudWatchMetricsConfig).
예외는 ④ 회원 구성(biz.members.*) — prod 반영 전 확인용으로 stg 도 내보내므로, stg 대시보드에는 ④ 만 그린다.

네 묶음: ① 로그인·가입 ② 챌린지 참여(초안·생성·가입) ③ 인증(자동 vs 수동)
④ 회원 구성(나잇대·성별·소셜·관심사·동의·활동·방 참여 경로·참여 방 수 순위).
묶음마다 첫 줄은 「대시보드에서 고른 기간 전체」의 합계·비율(숫자 칸) — 발표·보고 자료에 그대로 옮긴다. 그 아래가 1시간 단위 추세.
④ 는 게이지라 합계가 아니라 「지금 값」이고, 분포 칸(막대·원)도 가장 최근 값을 그린다.
사용: python business_dashboard.py [prod|stg]   (기본 prod)"""
import boto3, json, sys
R='ap-northeast-2'; ENV=sys.argv[1] if len(sys.argv)>1 else 'prod'
assert ENV in ('prod','stg'), ENV
NS=f'RuleUp/App/{ENV}'
cw=boto3.client('cloudwatch',region_name=R)
widgets=[]; y=0
def text(md, h=1):
    global y; widgets.append({'type':'text','x':0,'y':y,'width':24,'height':h,'properties':{'markdown':md}}); y+=h
def row(*ws, view='timeSeries', height=6):
    global y; x=0
    for title,metrics,extra in ws:
        w=24//len(ws)
        p={'title':title,'region':R,'view':view,'stacked':False,'period':3600,'metrics':metrics}
        if view=='singleValue': p.update({'setPeriodToTimeRange':True,'sparkline':False})
        p.update(extra or {})
        widgets.append({'type':'metric','x':x,'y':y,'width':w,'height':height,'properties':p}); x+=w
    y+=height
def totals(*ws): row(*ws, view='singleValue', height=4)
m=lambda name,*tags,stat='Sum',**o:[NS,name,*tags,{'stat':stat,**o}]
expr=lambda e,label,id,**o:[{'expression':e,'label':label,'id':id,**o}]
pct={'yAxis':{'left':{'min':0,'max':100}}}

def members_section():
    """④ 회원 구성 — MemberProfileMetrics. 게이지라 Maximum(두 태스크가 같은 값을 쓴다)."""
    g=lambda name,*tags,**o:m(f'biz.members.{name}.value',*tags,stat='Maximum',**o)
    latest={'setPeriodToTimeRange':False,'period':900}
    ages=[('10s','10대(14~19)'),('20s','20대'),('30s','30대'),('40s','40대'),('50s_plus','50대+'),('unknown','미입력')]
    genders=[('male','남'),('female','여')]   # 앱이 남·여 둘로만 받는다
    providers=[('kakao','카카오'),('naver','네이버'),('google','구글'),('apple','애플')]
    interests=[('exercise','운동'),('wake_sleep','기상·수면'),('diet_health','식습관·건강'),('study','학습'),('reading','독서'),
               ('mind','마음'),('finance','재테크'),('hobby','취미'),('housekeeping','정리·살림'),
               ('career_productivity','커리어·생산성'),('detox','절제·디톡스'),('etc','기타')]
    text(f'## ④ 회원 구성 ({ENV}) — 탈퇴·운영자·제외 계정 빼고, 10분마다 갱신. 상세(앱 버전·OS·국가·방 참여·탈퇴 시점)는 관리자 콘솔')
    totals(('지금 회원',[g('total',label='회원 수'),g('active','window','1d',label='1일 안 활동'),
                       g('active','window','7d',label='7일 안 활동')],latest),
           ('선택 동의율(%)',[g('total',id='t',visible=False),g('consent','type','marketing',id='mk',visible=False),
                        g('consent','type','event',id='ev',visible=False),
                        expr('IF(FILL(t,0)>0, 100*FILL(mk,0)/FILL(t,0))','마케팅','mkr'),
                        expr('IF(FILL(t,0)>0, 100*FILL(ev,0)/FILL(t,0))','이벤트','evr')],latest))
    row(('나잇대(만 나이)',[g('age','band',k,label=l) for k,l in ages],latest),
        ('성별',[g('gender','gender',k,label=l) for k,l in genders],latest),
        ('가입 소셜',[g('provider','provider',k,label=l) for k,l in providers],latest),
        view='pie')
    row(('관심사(복수 선택)',[g('interest','category',k,label=l) for k,l in interests],latest), view='bar')
    rooms_section(g, latest)
    row(('회원 수 · 활동 추이',[g('total',label='회원 수'),g('active','window','1d',label='1일 안 활동'),
                          g('active','window','7d',label='7일 안 활동')],None),
        ('나잇대 추이',[g('age','band',k,label=l) for k,l in ages],None))

def rooms_section(g, latest):
    """④ 안의 방 참여 — 방 개수가 아니라 사람 수. 만든 방은 가입 사건이 없는 멤버십이라 탐색·초대(들어간 방)와 갈린다.
    세 경로 칸(created_only·joined_only·both)은 겹치지 않아, 「만든 방이 있는 사람」은 created_only+both 로 더한다."""
    global y
    p=lambda k,**o:g('rooms','path',k,**o)
    paths=[('created_only','만든 방만'),('joined_only','들어간 방만(탐색·초대)'),('both','둘 다')]
    counts=[('1','1개'),('2','2개'),('3','3개'),('4','4개'),('5_plus','5개 이상')]
    totals(('챌린지 참여 중인 회원 — 사람 수(시작 전·진행 중인 방)',
            [p('created_only',id='co',visible=False),p('joined_only',id='jo',visible=False),p('both',id='bo',visible=False),
             expr('FILL(co,0)+FILL(jo,0)+FILL(bo,0)','참여 중','pa'),
             expr('FILL(co,0)+FILL(bo,0)','만든 방이 있음','pc'),
             expr('FILL(jo,0)+FILL(bo,0)','탐색·초대로 들어간 방이 있음','pj')],latest),
           ('참여율(%) — 회원 중',
            [g('total',id='mt',visible=False),p('created_only',id='c2',visible=False),
             p('joined_only',id='j2',visible=False),p('both',id='b2',visible=False),
             expr('IF(FILL(mt,0)>0, 100*(FILL(c2,0)+FILL(j2,0)+FILL(b2,0))/FILL(mt,0))','참여 중','pr'),
             expr('IF(FILL(mt,0)>0, 100*(FILL(c2,0)+FILL(b2,0))/FILL(mt,0))','만든 방이 있음','pcr'),
             expr('IF(FILL(mt,0)>0, 100*(FILL(j2,0)+FILL(b2,0))/FILL(mt,0))','들어간 방이 있음','pjr')],latest))
    row(('참여 경로 — 겹치지 않게 나눈 사람 수',[p(k,label=l) for k,l in paths],latest),
        ('참여 중인 방 개수별 회원 수',[g('rooms_per_member','rooms',k,label=l) for k,l in counts],latest),
        view='pie')
    # 순위는 사람을 가리켜 게이지로 못 만든다 — 앱이 10분마다 남기는 member_room_rank 로그(회원 ID 만, 닉네임은 콘솔)를 표로.
    # 가장 최근 10분 칸의 줄이 지금 순위다. 두 태스크가 같은 값을 남기므로 회원·칸으로 묶어 한 줄로 만든다.
    widgets.append({'type':'log','x':0,'y':y,'width':24,'height':8,'properties':{
        'title':'참여 방 수 순위 — 상위 10명(최근 10분 칸, 닉네임은 콘솔 /admin/dashboard/members/room-ranking)',
        'region':R,'view':'table',
        'query':f"SOURCE '/ecs/ruleup-{ENV}-api' | filter @message like /member_room_rank / "
                "| parse @message 'rank=* user=* rooms=* created=* joined=*' as r_rank, r_user, r_rooms, r_created, r_joined "
                "| stats min(r_rank) as rank_no, max(r_rooms) as rooms, max(r_created) as created, max(r_joined) as joined "
                "by bin(10m) as at, r_user | sort at desc, rank_no asc | limit 10"}})
    y+=8
    row(('참여 경로 추이',[p(k,label=l) for k,l in paths],None))

if ENV=='stg':
    members_section()
    cw.put_dashboard(DashboardName=f'ruleup-{ENV}-business',DashboardBody=json.dumps({'widgets':widgets},ensure_ascii=False))
    print('ok', len(widgets)); sys.exit(0)

# ---------------------------------------------------------------- ① 로그인 · 가입
text(f'## ① 로그인 · 가입 ({ENV})')
totals(('기간 합계',[m('biz.signup.count','result','success',label='가입 완료'),
                   m('biz.login.count','outcome','existing',label='기존 회원 로그인'),
                   m('biz.login.count','outcome','new_user',label='신규(가입 진행)')],None),
       ('로그인 성공률(%) · 가입 전환율(%)',[m('biz.login.count','outcome','existing',id='a',visible=False),
                   m('biz.login.count','outcome','new_user',id='b',visible=False),
                   m('biz.login.count','outcome','failure',id='f',visible=False),
                   m('biz.signup.count','result','success',id='s',visible=False),
                   expr('IF(FILL(a,0)+FILL(b,0)+FILL(f,0)>0, 100*(FILL(a,0)+FILL(b,0))/(FILL(a,0)+FILL(b,0)+FILL(f,0)))','로그인 성공률','r'),
                   # 신규 소셜 계정이 가입 화면으로 넘어간 수 대비 실제 가입 완료
                   expr('IF(FILL(b,0)>0, 100*FILL(s,0)/FILL(b,0))','가입 전환율','cv')],None))
row(('가입',[m('biz.signup.count','result','success',label='가입 완료'),m('biz.signup.count','result','failure',label='가입 실패')],None),
    ('로그인',[m('biz.login.count','outcome','existing',label='기존 회원 로그인'),m('biz.login.count','outcome','new_user',label='신규(가입 진행)'),
              m('biz.login.count','outcome','failure',label='실패')],None))

# ---------------------------------------------------------------- ② 챌린지 참여
text('## ② 챌린지 참여 — 생성(AI·추천 탭·복제) · 가입(탐색·초대)')
d=lambda origin,result,**o:m('biz.challenge.draft.count','origin',origin,'result',result,**o)
c=lambda origin,edited,**o:m('biz.challenge.created.count','origin',origin,'edited',edited,**o)
j=lambda source,**o:m('biz.challenge.joined.count','source',source,**o)
totals(('생성 vs 가입 (기간 합계)',[c('ai','yes',id='c1',visible=False),c('ai','no',id='c2',visible=False),
                              c('template','yes',id='c3',visible=False),c('template','no',id='c4',visible=False),
                              c('clone','yes',id='c5',visible=False),c('clone','no',id='c6',visible=False),
                              expr('FILL(c1,0)+FILL(c2,0)+FILL(c3,0)+FILL(c4,0)+FILL(c5,0)+FILL(c6,0)','챌린지 생성','ct'),
                              j('explore',label='탐색으로 가입'),j('invite',label='초대로 가입')],None),
       ('초안 수정률(%) · AI 초안 성공률(%)',[c('ai','yes',id='e1',visible=False),c('ai','no',id='e2',visible=False),
                              c('template','yes',id='e3',visible=False),c('template','no',id='e4',visible=False),
                              c('clone','yes',id='e5',visible=False),c('clone','no',id='e6',visible=False),
                              expr('IF(FILL(e1,0)+FILL(e2,0)+FILL(e3,0)+FILL(e4,0)+FILL(e5,0)+FILL(e6,0)>0, '
                                   '100*(FILL(e1,0)+FILL(e3,0)+FILL(e5,0))/(FILL(e1,0)+FILL(e2,0)+FILL(e3,0)+FILL(e4,0)+FILL(e5,0)+FILL(e6,0)))',
                                   '초안을 고친 비율','er'),
                              d('ai','success',id='as',visible=False),d('ai','blocked',id='ab',visible=False),d('ai','failure',id='af',visible=False),
                              expr('IF(FILL(as,0)+FILL(ab,0)+FILL(af,0)>0, 100*FILL(as,0)/(FILL(as,0)+FILL(ab,0)+FILL(af,0)))','AI 초안 성공률','ar')],None))
row(('초안 생성 경로',[d('ai','success',label='AI(LLM) 성공'),d('ai','blocked',label='AI 정책 차단'),d('ai','failure',label='AI LLM 실패'),
                      d('template','success',label='추천 탭(템플릿)'),d('clone','success',label='복제')],None),
    ('챌린지 생성 경로',[expr('FILL(a1,0)+FILL(a2,0)','AI','ga'),expr('FILL(t1,0)+FILL(t2,0)','추천 탭','gt'),expr('FILL(k1,0)+FILL(k2,0)','복제','gk'),
                        c('ai','yes',id='a1',visible=False),c('ai','no',id='a2',visible=False),
                        c('template','yes',id='t1',visible=False),c('template','no',id='t2',visible=False),
                        c('clone','yes',id='k1',visible=False),c('clone','no',id='k2',visible=False)],None),
    ('초안 → 생성 전환율(%) — 경로별',[d('ai','success',id='da',visible=False),d('template','success',id='dt',visible=False),d('clone','success',id='dk',visible=False),
                        c('ai','yes',id='x1',visible=False),c('ai','no',id='x2',visible=False),
                        c('template','yes',id='x3',visible=False),c('template','no',id='x4',visible=False),
                        c('clone','yes',id='x5',visible=False),c('clone','no',id='x6',visible=False),
                        expr('IF(FILL(da,0)>0, 100*(FILL(x1,0)+FILL(x2,0))/FILL(da,0))','AI','pa'),
                        expr('IF(FILL(dt,0)>0, 100*(FILL(x3,0)+FILL(x4,0))/FILL(dt,0))','추천 탭','pt'),
                        expr('IF(FILL(dk,0)>0, 100*(FILL(x5,0)+FILL(x6,0))/FILL(dk,0))','복제','pk')],{**pct,'period':86400}))
row(('초안 수정 여부 — 경로별',[c('ai','yes',label='AI · 고침'),c('ai','no',label='AI · 그대로'),
                             c('template','yes',label='추천 탭 · 고침'),c('template','no',label='추천 탭 · 그대로'),
                             c('clone','yes',label='복제 · 고침'),c('clone','no',label='복제 · 그대로')],None),
    ('가입 경로',[j('explore',label='탐색·상세에서 가입'),j('invite',label='초대 링크로 가입')],None))

# ---------------------------------------------------------------- ③ 인증 — 자동 vs 수동
text('## ③ 인증 — 자동 vs 수동 (성공률은 확정이 끝난 그제 귀속분)')
t=lambda typ,status,**o:m('biz.verification.finalized_by_type.value','type',typ,'status',status,stat='Maximum',**o)
totals(('인증 시도 (기간 합계)',[m('biz.verification.attempt.count','method','sync',label='자동(sync 전송)'),
                              m('biz.verification.attempt.count','method','manual',label='수동 체크')],None),
       ('자동 인증 성공률(%) · 성공 중 자동 비율(%) — 그제',[t('auto','success',id='as2',visible=False),t('auto','failed',id='af2',visible=False),
                              t('manual','success',id='ms2',visible=False),
                              expr('IF(FILL(as2,0)+FILL(af2,0)>0, 100*FILL(as2,0)/(FILL(as2,0)+FILL(af2,0)))','자동 인증 성공률','asr'),
                              expr('IF(FILL(as2,0)+FILL(ms2,0)>0, 100*FILL(as2,0)/(FILL(as2,0)+FILL(ms2,0)))','성공 중 자동 비율','ash')],
        {'setPeriodToTimeRange':False,'period':300}))
row(('인증 시도 — 자동 vs 수동',[m('biz.verification.attempt.count','method','sync',label='자동(sync)'),m('biz.verification.attempt.count','method','manual',label='수동')],None),
    # 수동 방은 체크하지 않은 날 행이 생기지 않아 「수동 실패」가 없다 — 수동은 성공 건수로만 비교한다.
    ('확정된 그제 판정 — 자동 성공·실패 / 수동 성공',[t('auto','success',label='자동 성공'),t('auto','failed',label='자동 실패'),
                              t('manual','success',label='수동 성공')],{'period':300}),
    ('자동 인증 성공률(%) — 그제',[t('auto','success',id='s3',visible=False),t('auto','failed',id='f3',visible=False),
                              expr('IF(FILL(s3,0)+FILL(f3,0)>0, 100*FILL(s3,0)/(FILL(s3,0)+FILL(f3,0)))','자동 인증 성공률','r3')],{**pct,'period':300}))
row(('오늘 진행률(%) — 판정 행이 열린 멤버 중 성공',[m('biz.verification.today.value','status','success',stat='Maximum',id='s',visible=False),
                        m('biz.verification.today.value','status','pending',stat='Maximum',id='p',visible=False),
                        expr('IF(FILL(s,0)+FILL(p,0)>0, 100*FILL(s,0)/(FILL(s,0)+FILL(p,0)))','오늘 진행률','tr')],{**pct,'period':300}),
    ('전체 인증 성공률(%) — 그제',[m('biz.verification.finalized.value','status','success',stat='Maximum',id='fs',visible=False),
                        m('biz.verification.finalized.value','status','failed',stat='Maximum',id='ff',visible=False),
                        expr('IF(FILL(fs,0)+FILL(ff,0)>0, 100*FILL(fs,0)/(FILL(fs,0)+FILL(ff,0)))','인증 성공률','sr')],{**pct,'period':300}))

# ---------------------------------------------------------------- ④ 회원 구성
members_section()
cw.put_dashboard(DashboardName=f'ruleup-{ENV}-business',DashboardBody=json.dumps({'widgets':widgets},ensure_ascii=False))
print('ok', len(widgets))
