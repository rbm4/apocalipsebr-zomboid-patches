"""Actual Lua generic snapshots: sparse patches, committed baselines and in-flight edits."""
import os
from pathlib import Path
from lupa import LuaRuntime

MOD = Path(os.environ.get('APOCBR_TEST_BEYONDTEN', r'C:\Users\ricar\Zomboid\Workshop\apocbr\Contents\mods\BeyondTen\42'))
lua = LuaRuntime()
lua.execute('''
    function isServer() return true end
    logs={};function print(value) logs[#logs+1]=value end
    Events={OnTickEvenPaused={Add=function(fn) tick=fn end}}
    requests={};responses={};sequence=0
    context={characterId='trusted',session='session'}
    player={}
    local function admit(request)
        if reject then return {accepted=false,status=reject} end
        sequence=sequence+1;request.id=tostring(sequence);requests[#requests+1]=request
        return {accepted=true,id=request.id}
    end
    ApocBRModEntities={
        define=function() end,
        character=function() return {status='OK',characterId=context.characterId,session=context.session} end,
        submit=function(name,op,keys,values,revision)
            return admit({kind='entity',op=op,keys=keys,values=values,revision=revision})
        end,
        submitCharacter=function(name,op,p,session,rows)
            return admit({kind='collection',op=op,rows=rows})
        end,
        poll=function() local result=responses;responses={};return result end
    }
    function complete(request,status,revision,values)
        local result={id=request.id,status=status,revision=revision,values=values}
        if request.kind=='collection' and status=='OK' then
            result.rows={}
            for _,row in ipairs(request.rows) do
                local values={character_id='trusted'}
                for name,value in pairs(row.values) do values[name]=value end
                result.rows[#result.rows+1]={values=values,revision=tostring(tonumber(row.revision)+1)}
            end
        end
        responses[#responses+1]=result;tick()
    end
    function last() return requests[#requests] end
''')
lua.execute((MOD / 'media/lua/server/ApocBR/Entities.lua').read_text(encoding='utf-8'))
lua.execute('''
    repo=ApocBREntities.define('Test',{table='test',fields={
        character_id={type='uuid',id=true},perk_id={type='string',id=true},
        xp={type='number'},enabled={type='boolean'},count={type='integer'}
    }})
    repo:find({character_id='trusted',perk_id='A'},function(result) entity=result.entity end)
    complete(last(),'OK','9007199254740993',
        {character_id='trusted',perk_id='A',xp=10,enabled=false,count=1})
    local before=#requests
    entity:patch(function(result) assert(result.unchanged and result.revision=='9007199254740993') end)
    assert(#requests==before and not entity:isDirty())
    entity:set('xp',12);entity:set('xp',10)
    assert(not entity:isDirty())
    entity:patch(function(result) assert(result.unchanged) end);assert(#requests==before)
    entity:set('xp',20)
    entity:patch(function(result) assert(result.status=='CONFLICT') end)
    assert(last().op=='patch' and last().values.xp==20 and last().values.enabled==nil and last().values.count==nil)
    complete(last(),'CONFLICT')
    assert(entity:isDirty() and entity._snapshot.xp==10)
    entity:patch(function(result) assert(result.status=='OK') end)
    local flight=last()
    entity:set('xp',25)
    assert(flight.values.xp==20)
    complete(flight,'OK','9007199254740994')
    assert(entity:isDirty() and entity._snapshot.xp==20 and entity:getRevision()=='9007199254740994')
    entity:patch(function() end);flight=last();entity:set('xp',20)
    complete(flight,'OK','9007199254740995')
    assert(entity:isDirty()) -- reverting during flight must not lose the compensating patch
    entity:patch(function() end)
    assert(last().values.xp==20 and last().revision=='9007199254740995')
    complete(last(),'OK','9007199254740996');assert(not entity:isDirty())
    entity:set('enabled',true);entity:patch(function() end)
    assert(last().values.enabled==true and last().values.xp==nil)
    complete(last(),'DATABASE_ERROR');assert(entity:isDirty())
    reject='UNAVAILABLE';entity:patch(function(result) assert(result.status=='UNAVAILABLE') end)
    assert(entity:isDirty() and not entity._inFlight);reject=nil
    entity:save(function() end)
    assert(last().op=='save' and last().values.xp==20 and last().values.count==1)
    complete(last(),'OK','9007199254740997');assert(not entity:isDirty())
    fresh=repo:new({character_id='trusted',perk_id='B',xp=0,enabled=false,count=0})
    fresh:patch(function() end)
    assert(last().op=='save' and last().revision=='0' and last().values.enabled==false)
    complete(last(),'OK','1');assert(not fresh:isDirty())
''')
print('PASS entity no-ops, revert, sparse fields, bigint revisions, errors, in-flight edits and insert/save compatibility')

lua.execute('''
    local loaded={
        {values={character_id='trusted',perk_id='A',xp=10,enabled=false,count=1},revision='7'},
        {values={character_id='trusted',perk_id='B',xp=30,enabled=true,count=2},revision='9'}
    }
    snapshot=repo:characterSnapshot(loaded)
    loaded[1].values.xp=999 -- snapshot must detach database rows
    rows={
        {values={perk_id='A',xp=10,enabled=false,count=1}},
        {values={perk_id='B',xp=30,enabled=true,count=2}}
    }
    local before=#requests
    snapshot:patch(player,context,rows,function(result) assert(result.unchanged and #result.rows==0) end)
    assert(#requests==before)
    rows[1].values.xp=15
    snapshot:patch(player,context,rows,function(result) assert(result.status=='OK') end)
    local flight=last()
    assert(flight.op=='patch' and #flight.rows==1 and flight.rows[1].revision=='7')
    assert(flight.rows[1].values.perk_id=='A' and flight.rows[1].values.xp==15)
    assert(flight.rows[1].values.enabled==nil and flight.rows[1].values.count==nil)
    assert(not pcall(function() snapshot:patch(player,context,rows,function() end) end))
    rows[1].values.count=3
    complete(flight,'OK')
    snapshot:patch(player,context,rows,function() end)
    assert(#last().rows==1 and last().rows[1].revision=='8' and last().rows[1].values.count==3)
    assert(last().rows[1].values.xp==nil)
    complete(last(),'OK')
    before=#requests
    snapshot:patch(player,context,rows,function(result) assert(result.unchanged) end)
    assert(#requests==before) -- unchanged B retains revision 9 and never enters SQL
    rows[2].values.enabled=false
    snapshot:patch(player,context,rows,function() end)
    assert(last().rows[1].values.enabled==false and last().rows[1].revision=='9')
    complete(last(),'CONFLICT')
    snapshot:patch(player,context,rows,function() end)
    assert(last().rows[1].revision=='9' and last().rows[1].values.enabled==false)
    complete(last(),'OK')
    rows[3]={values={perk_id='C',xp=0,enabled=false,count=0}}
    snapshot:patch(player,context,rows,function() end)
    assert(#last().rows==1 and last().rows[1].revision=='0' and last().rows[1].values.count==0)
    complete(last(),'OK')
    before=#requests
    snapshot:patch(player,context,rows,function(result) assert(result.unchanged) end)
    assert(#requests==before)
    rows[1].values.xp=0
    snapshot:patch(player,context,rows,function() end)
    assert(#last().rows==1 and last().rows[1].values.xp==0)
    complete(last(),'OUTCOME_UNKNOWN')
    snapshot:patch(player,context,rows,function() end)
    assert(last().rows[1].revision=='9') -- baseline only advances on OK
    complete(last(),'OK')
    rows[2].values.count=4
    reject='BUSY';snapshot:patch(player,context,rows,function(result) assert(result.status=='BUSY') end)
    reject=nil
    snapshot:patch(player,context,rows,function() end)
    assert(last().rows[1].revision=='10')
    -- Malformed acknowledgement must neither advance the baseline nor wedge the tracker.
    responses={{id=last().id,status='OK',rows={}}};tick()
    snapshot:patch(player,context,rows,function() end)
    assert(last().rows[1].revision=='10');complete(last(),'OK')
    assert(not pcall(function() snapshot:patch(player,{characterId='other'},rows,function() end) end))
    before=#requests
    local prior=context
    context={characterId='trusted',session='replacement'}
    snapshot:patch(player,prior,rows,function(result) assert(result.status=='STALE_SESSION') end)
    assert(#requests==before)
''')
print('PASS generic character snapshots: changed rows/fields only, zeroes, inserts, retries and failed acknowledgement retention')
