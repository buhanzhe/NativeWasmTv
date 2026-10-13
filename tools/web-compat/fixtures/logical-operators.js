(function(){
 var results=[],failed=[];
 function check(op,initial,expected,shouldWrite){
  var reads=0,writes=0,rhs=0,keys=0,bases=0,value=initial;
  var target={get x(){reads++;return value;},set x(v){writes++;value=v;}};
  function base(){bases++;return target;}
  function key(){keys++;return 'x';}
  function right(){rhs++;return 7;}
  var answer;
  if(op==='and')answer=(base()[key()]&&=right());
  if(op==='or')answer=(base()[key()]||=right());
  if(op==='nullish')answer=(base()[key()]??=right());
  var ok=answer===expected&&value===expected&&reads===1&&writes===shouldWrite&&rhs===shouldWrite&&keys===1&&bases===1;
  var row={op:op,initial:String(initial),pass:ok,answer:answer,value:value,reads:reads,writes:writes,rhs:rhs,keys:keys,bases:bases};
  results.push(row);if(!ok)failed.push(row);
 }
 check('and',1,7,1);check('and',0,0,0);
 check('or',0,7,1);check('or',1,1,0);
 check('nullish',null,7,1);check('nullish',undefined,7,1);
 check('nullish',0,0,0);check('nullish',false,false,0);check('nullish','', '',0);
 var report={pass:failed.length===0,cases:results.length,results:results,failed:failed};
 var text=JSON.stringify(report);document.body.textContent=text;console.log('LOGICAL_FIXTURE_RESULT '+text);
 if(failed.length)throw new Error('Logical assignment semantics failed');
}());
