// Same ES5 program is executed in each engine against the same actual DOM.
var proof = document.createElement('button');
proof.id = 'proof'; proof.textContent = 'Waiting';
document.body.appendChild(proof);
if (document.getElementById('proof') !== proof) throw Error('DOM identity mismatch');
proof.addEventListener('click', function () { proof.textContent = 'Clicked'; });
proof.click();
setTimeout(function () { document.body.setAttribute('data-timer', 'done'); }, 5);
var start = Date.now();
for (var i = 0; i < 100; i++) {
    proof.style.width = (100 + i) + 'px';
    var measured = proof.offsetWidth;
}
document.body.setAttribute('data-loop-ms', String(Date.now() - start));
